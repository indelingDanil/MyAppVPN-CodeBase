package main

// Dialing the exit host with a DNS fallback.
//
// Some mobile networks (whitelist mode, blocked Private DNS / DoT) cannot
// resolve the exit host while plain TCP to it still works. The exit transport
// resolves the host itself (system resolver, 4 s cap, IPv4 preferred), keeps
// the last good IPv4 in a small JSON cache file, and dials that cached IP when
// resolution fails. `-ip` skips DNS entirely. Only the dialed address changes:
// the request URL — and so the Host header — keeps the original host name.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

// dnsTimeout caps one system resolution of the exit host (var for tests).
var dnsTimeout = 4 * time.Second

// ipCacheRefresh: a cache entry that still matches is rewritten (to bump "at")
// at most this often.
const ipCacheRefresh = time.Hour

type ipCacheEntry struct {
	Host string `json:"host"`
	IP   string `json:"ip"`
	At   int64  `json:"at"`
}

type exitDialer struct {
	cachePath string // -ipcache ("" = no cache file)
	override  string // -ip (skip DNS)
	lookup    func(ctx context.Context, host string) ([]net.IPAddr, error)
	dial      func(ctx context.Context, network, addr string) (net.Conn, error)

	mu  sync.Mutex
	mem *ipCacheEntry // last entry read or written
}

func newExitDialer() *exitDialer {
	nd := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	return &exitDialer{lookup: net.DefaultResolver.LookupIPAddr, dial: nd.DialContext}
}

// exitDial / exitTransport carry every request to the exit (v2). main
// fills cachePath/override from the flags before the first request.
var (
	exitDial      = newExitDialer()
	exitTransport = newExitTransport(exitDial)
)

func newExitTransport(d *exitDialer) *http.Transport {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.DialContext = d.DialContext
	// A session can hold two down polls (make-before-break), an up POST and an
	// ack at once: keep their connections for reuse instead of closing extras.
	t.MaxIdleConnsPerHost = 4
	return t
}

func newExitClient() *http.Client { return &http.Client{Transport: exitTransport} }

// splitIPs returns the IPv4 addresses (as strings) and the IPv6 ones.
func splitIPs(addrs []net.IPAddr) (v4, v6 []string) {
	for _, a := range addrs {
		if ip4 := a.IP.To4(); ip4 != nil {
			v4 = append(v4, ip4.String())
		} else if a.IP != nil {
			v6 = append(v6, a.IP.String())
		}
	}
	return
}

// cached returns the cached IPv4 for host, or nil.
func (d *exitDialer) cached(host string) *ipCacheEntry {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.mem == nil && d.cachePath != "" {
		if b, err := os.ReadFile(d.cachePath); err == nil {
			var e ipCacheEntry
			if json.Unmarshal(b, &e) == nil {
				d.mem = &e
			}
		}
	}
	if d.mem == nil || !strings.EqualFold(d.mem.Host, host) {
		return nil
	}
	if ip := net.ParseIP(d.mem.IP); ip == nil || ip.To4() == nil {
		return nil
	}
	e := *d.mem
	return &e
}

// store remembers ip as host's address and writes the cache file (atomically).
func (d *exitDialer) store(host, ip string) {
	if d.cachePath == "" {
		return
	}
	d.cached(host) // load the file once, so an unchanged entry is not rewritten
	d.mu.Lock()
	defer d.mu.Unlock()
	now := time.Now()
	if d.mem != nil && strings.EqualFold(d.mem.Host, host) && d.mem.IP == ip &&
		now.Sub(time.Unix(d.mem.At, 0)) < ipCacheRefresh {
		return
	}
	e := &ipCacheEntry{Host: host, IP: ip, At: now.Unix()}
	b, _ := json.Marshal(e)
	tmp := d.cachePath + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		log.Printf("ipcache: write %s: %v", filepath.Base(d.cachePath), err)
		return
	}
	if err := os.Rename(tmp, d.cachePath); err != nil {
		log.Printf("ipcache: rename: %v", err)
		_ = os.Remove(tmp)
		return
	}
	d.mem = e
}

var errNoAddress = errors.New("no addresses")

// resolve picks the address to dial for host and says where it came from:
// "override" (-ip), "dns" (system IPv4), "dns6" (system, IPv6 only), "cache".
func (d *exitDialer) resolve(ctx context.Context, host string) (ip, how string, err error) {
	if d.override != "" {
		return d.override, "override", nil
	}
	lctx, cancel := context.WithTimeout(ctx, dnsTimeout)
	addrs, err := d.lookup(lctx, host)
	cancel()
	v4, v6 := splitIPs(addrs)
	if len(v4) > 0 {
		d.store(host, v4[0])
		return v4[0], "dns", nil
	}
	if err == nil {
		err = errNoAddress
	}
	if ctx.Err() == nil {
		if e := d.cached(host); e != nil {
			if *flagDebug {
				log.Printf("exit dns %s failed (%s); using cached %s", host, errStr(err), e.IP)
			}
			return e.IP, "cache", nil
		}
	}
	if len(v6) > 0 {
		return v6[0], "dns6", nil
	}
	return "", "", err
}

// DialContext is the exit transport's dialer: IP literals are dialed as is,
// host names via resolve.
func (d *exitDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || net.ParseIP(host) != nil {
		return d.dial(ctx, network, addr)
	}
	ip, how, err := d.resolve(ctx, host)
	if err != nil {
		return nil, &net.OpError{Op: "dial", Net: network, Err: fmt.Errorf("resolve %s: %w", host, err)}
	}
	if *flagDebug {
		log.Printf("exit dial %s via %s → %s", host, how, ip)
	}
	return d.dial(ctx, network, net.JoinHostPort(ip, port))
}

// errStr maps a network error to a short, token-free string.
func errStr(err error) string {
	if err == nil {
		return ""
	}
	err = redact(err)
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		switch {
		case dnsErr.IsNotFound:
			return "no such host"
		case dnsErr.IsTimeout:
			return "dns timeout"
		}
	}
	switch {
	case errors.Is(err, errNoAddress):
		return "no addresses"
	case errors.Is(err, syscall.EPERM), errors.Is(err, syscall.EACCES), errors.Is(err, os.ErrPermission):
		return "permission denied"
	case errors.Is(err, syscall.ECONNREFUSED):
		return "connection refused"
	case errors.Is(err, syscall.ENETUNREACH):
		return "network is unreachable"
	case errors.Is(err, syscall.EHOSTUNREACH):
		return "host is unreachable"
	case errors.Is(err, syscall.ECONNRESET):
		return "connection reset"
	case errors.Is(err, context.DeadlineExceeded):
		return "i/o timeout"
	case errors.Is(err, context.Canceled):
		return "canceled"
	case errors.Is(err, io.ErrUnexpectedEOF), errors.Is(err, io.EOF):
		return "eof"
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return "i/o timeout"
	}
	s := strings.ToLower(err.Error())
	for _, m := range []struct{ sub, out string }{
		{"permission denied", "permission denied"},
		{"access permissions", "permission denied"}, // Windows WSAEACCES
		{"no such host", "no such host"},
		{"refused", "connection refused"},
		{"network is unreachable", "network is unreachable"},
		{"unreachable network", "network is unreachable"},
		{"host is unreachable", "host is unreachable"},
		{"unreachable host", "host is unreachable"},
		{"reset by peer", "connection reset"},
		{"forcibly closed", "connection reset"},
		{"timeout", "i/o timeout"},
		{"timed out", "i/o timeout"},
		{"failed to respond", "i/o timeout"},
	} {
		if strings.Contains(s, m.sub) {
			return m.out
		}
	}
	if len(s) > 80 {
		s = s[:80]
	}
	return s
}
