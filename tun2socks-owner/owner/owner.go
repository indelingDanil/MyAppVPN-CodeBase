// SPDX-License-Identifier: GPL-3.0-or-later

// Package owner drops tun flows whose socket isn't owned by an app routed through the VPN.
//
// Why: an app OUTSIDE the VPN (split tunnel, or the VPN app's own UID) can still bind its socket
// to tun0 (SO_BINDTODEVICE). Its packets then enter the TUN and tun2socks would forward them
// through the proxy, revealing the exit IP to an app that is supposed to see the direct network
// (GitHub issue #8). Android exposes ConnectivityManager.getConnectionOwnerUid() to the VPN app
// (API 29+): it returns INVALID_UID (-1) for sockets whose UID isn't covered by the VPN. That call
// is Java-only, so the app runs a tiny line protocol on an abstract unix socket:
//
//	request:  "tcp|udp <srcIP>:<srcPort> <dstIP>:<dstPort>\n"  (IPv6 in brackets)
//	response: "<uid>\n"   -1 = not ours → drop; any other integer → allow
//	          "err\n"     lookup failed → allow or drop depending on Strict
//
// Connections to the socket are kept open and reused (one request in flight per connection).
package owner

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
)

// InvalidUID is android.os.Process.INVALID_UID.
const InvalidUID = -1

// ErrNotOwned is returned by the wrapped dialer for a flow that must not be proxied.
var ErrNotOwned = errors.New("owner: flow is not owned by a tunnelled app")

// DefaultBypass are the TUN DNS placeholders (MyVpnService.buildTun): queries to them are
// hijacked to Xray's DNS anyway and are allowed without a lookup.
var DefaultBypass = []netip.AddrPort{
	netip.MustParseAddrPort("172.19.0.2:53"),
	netip.MustParseAddrPort("[fdfe:dcba:9876::2]:53"),
}

type Config struct {
	Network string // "unix"
	Address string // "@name" = abstract namespace (Linux/Android), or a filesystem path

	Timeout       time.Duration // whole query budget incl. waiting for a slot (default 100ms)
	CacheTTL      time.Duration // verdict cache per 5-tuple (default 2s; <0 disables)
	Strict        bool          // drop the flow when the query fails / times out
	SkipUDP       bool          // allow all UDP without a query (kernels without UDP inet_diag)
	Bypass        []netip.AddrPort
	MaxConcurrent int // parallel queries = max open connections (default 16)

	Logf func(format string, args ...any) // denials / failures (optional)
}

type Checker struct {
	cfg    Config
	bypass map[netip.AddrPort]struct{}
	sem    chan struct{}
	idle   chan *conn

	mu        sync.Mutex
	cache     map[flowKey]cached
	lastSweep time.Time

	now func() time.Time // for tests
}

type flowKey struct {
	udp      bool
	src, dst netip.AddrPort
}

type cached struct {
	uid int
	exp time.Time
}

type conn struct {
	net.Conn
	r *bufio.Reader
}

func New(cfg Config) *Checker {
	if cfg.Network == "" {
		cfg.Network = "unix"
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 100 * time.Millisecond
	}
	if cfg.CacheTTL == 0 {
		cfg.CacheTTL = 2 * time.Second
	}
	if cfg.MaxConcurrent <= 0 {
		cfg.MaxConcurrent = 16
	}
	c := &Checker{
		cfg:    cfg,
		bypass: make(map[netip.AddrPort]struct{}, len(cfg.Bypass)),
		sem:    make(chan struct{}, cfg.MaxConcurrent),
		idle:   make(chan *conn, cfg.MaxConcurrent),
		cache:  make(map[flowKey]cached),
		now:    time.Now,
	}
	for _, ap := range cfg.Bypass {
		c.bypass[norm(ap)] = struct{}{}
	}
	return c
}

func norm(ap netip.AddrPort) netip.AddrPort {
	return netip.AddrPortFrom(ap.Addr().Unmap(), ap.Port())
}

// Allow reports whether the flow may be proxied. uid is the owner answer (or InvalidUID when the
// lookup failed / was skipped); err is the lookup error, if any.
func (c *Checker) Allow(udp bool, src, dst netip.AddrPort) (ok bool, uid int, err error) {
	src, dst = norm(src), norm(dst)
	if udp && c.cfg.SkipUDP {
		return true, InvalidUID, nil
	}
	if _, bypass := c.bypass[dst]; bypass {
		return true, InvalidUID, nil
	}
	k := flowKey{udp, src, dst}
	if uid, hit := c.lookupCache(k); hit {
		return uid != InvalidUID, uid, nil
	}
	uid, err = c.query(k)
	if err != nil {
		if c.cfg.Logf != nil {
			c.cfg.Logf("[OWNER] %s %s -> %s: query failed (strict=%v): %v", proto(udp), src, dst, c.cfg.Strict, err)
		}
		return !c.cfg.Strict, InvalidUID, err
	}
	c.storeCache(k, uid)
	if uid == InvalidUID && c.cfg.Logf != nil {
		c.cfg.Logf("[OWNER] %s %s -> %s: dropped, socket owner is outside the VPN", proto(udp), src, dst)
	}
	return uid != InvalidUID, uid, nil
}

func proto(udp bool) string {
	if udp {
		return "udp"
	}
	return "tcp"
}

func (c *Checker) lookupCache(k flowKey) (int, bool) {
	if c.cfg.CacheTTL < 0 {
		return 0, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.cache[k]
	if !ok || c.now().After(e.exp) {
		return 0, false
	}
	return e.uid, true
}

func (c *Checker) storeCache(k flowKey, uid int) {
	if c.cfg.CacheTTL < 0 {
		return
	}
	now := c.now()
	c.mu.Lock()
	defer c.mu.Unlock()
	if now.Sub(c.lastSweep) > c.cfg.CacheTTL {
		for key, e := range c.cache {
			if now.After(e.exp) {
				delete(c.cache, key)
			}
		}
		c.lastSweep = now
	}
	c.cache[k] = cached{uid, now.Add(c.cfg.CacheTTL)}
}

func (c *Checker) query(k flowKey) (int, error) {
	deadline := time.Now().Add(c.cfg.Timeout)
	timer := time.NewTimer(c.cfg.Timeout)
	defer timer.Stop()
	select {
	case c.sem <- struct{}{}:
	case <-timer.C:
		return InvalidUID, errors.New("timed out waiting for a query slot")
	}
	defer func() { <-c.sem }()

	cn, err := c.getConn(deadline)
	if err != nil {
		return InvalidUID, err
	}
	uid, err := roundTrip(cn, deadline, fmt.Sprintf("%s %s %s\n", proto(k.udp), k.src, k.dst))
	if err != nil {
		cn.Close()
		return InvalidUID, err
	}
	select {
	case c.idle <- cn:
	default:
		cn.Close()
	}
	return uid, nil
}

func (c *Checker) getConn(deadline time.Time) (*conn, error) {
	select {
	case cn := <-c.idle:
		return cn, nil
	default:
	}
	d := net.Dialer{Deadline: deadline}
	nc, err := d.Dial(c.cfg.Network, c.cfg.Address)
	if err != nil {
		return nil, err
	}
	return &conn{nc, bufio.NewReaderSize(nc, 64)}, nil
}

func roundTrip(cn *conn, deadline time.Time, req string) (int, error) {
	if err := cn.SetDeadline(deadline); err != nil {
		return InvalidUID, err
	}
	if _, err := cn.Write([]byte(req)); err != nil {
		return InvalidUID, err
	}
	line, err := cn.r.ReadString('\n')
	if err != nil {
		return InvalidUID, err
	}
	line = strings.TrimSpace(line)
	uid, err := strconv.Atoi(line)
	if err != nil {
		return InvalidUID, fmt.Errorf("server answered %q", line)
	}
	return uid, nil
}

// Close drops pooled connections.
func (c *Checker) Close() {
	for {
		select {
		case cn := <-c.idle:
			cn.Close()
		default:
			return
		}
	}
}

// Dialer wraps the tunnel's proxy dialer: every new TCP connection / UDP session is checked
// before it is dialed. A refused TCP flow is closed by the tunnel (handleTCPConn returns), a
// refused UDP session is dropped (its gVisor endpoint is closed, the datagrams are discarded).
type Dialer struct {
	proxy.Dialer
	C *Checker
}

func (d *Dialer) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	if ok, _, _ := d.C.Allow(false, m.SourceAddrPort(), m.DestinationAddrPort()); !ok {
		return nil, ErrNotOwned
	}
	return d.Dialer.DialContext(ctx, m)
}

func (d *Dialer) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	if ok, _, _ := d.C.Allow(true, m.SourceAddrPort(), m.DestinationAddrPort()); !ok {
		return nil, ErrNotOwned
	}
	return d.Dialer.DialUDP(m)
}

// ParseBypass parses a comma-separated ip:port list ("" → none).
func ParseBypass(s string) ([]netip.AddrPort, error) {
	var out []netip.AddrPort
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p == "" {
			continue
		}
		ap, err := netip.ParseAddrPort(p)
		if err != nil {
			return nil, err
		}
		out = append(out, ap)
	}
	return out, nil
}
