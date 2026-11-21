// SPDX-License-Identifier: GPL-3.0-or-later

package owner

import (
	"bufio"
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

// fakeServer mimics OwnerUidServer.kt: one goroutine per connection, one answer per line.
type fakeServer struct {
	ln      net.Listener
	queries atomic.Int32
	conns   atomic.Int32

	mu      sync.Mutex
	answer  func(req string) string // returns the raw response line (without \n), "" = no answer
	lastReq string
}

func startServer(t *testing.T, answer func(string) string) (*fakeServer, string) {
	t.Helper()
	dir, err := os.MkdirTemp("", "own")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "s")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Skipf("unix sockets unavailable: %v", err)
	}
	s := &fakeServer{ln: ln, answer: answer}
	t.Cleanup(func() { ln.Close(); os.RemoveAll(dir) })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			s.conns.Add(1)
			go func(c net.Conn) {
				defer c.Close()
				r := bufio.NewReader(c)
				for {
					line, err := r.ReadString('\n')
					if err != nil {
						return
					}
					s.queries.Add(1)
					req := strings.TrimSuffix(line, "\n")
					s.mu.Lock()
					s.lastReq = req
					f := s.answer
					s.mu.Unlock()
					resp := f(req)
					if resp == "" {
						continue // simulate a hung server
					}
					if _, err := c.Write([]byte(resp + "\n")); err != nil {
						return
					}
				}
			}(c)
		}
	}()
	return s, path
}

func (s *fakeServer) last() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastReq
}

var (
	src4 = netip.MustParseAddrPort("172.19.0.1:40000")
	dst4 = netip.MustParseAddrPort("93.184.216.34:443")
	src6 = netip.MustParseAddrPort("[fdfe:dcba:9876::1]:40001")
	dst6 = netip.MustParseAddrPort("[2001:db8::1]:443")
)

func newChecker(path string, mod func(*Config)) *Checker {
	cfg := Config{Address: path, Bypass: DefaultBypass}
	if mod != nil {
		mod(&cfg)
	}
	return New(cfg)
}

func TestAllowOwnedFlow(t *testing.T) {
	s, path := startServer(t, func(string) string { return "10123" })
	c := newChecker(path, nil)
	defer c.Close()
	ok, uid, err := c.Allow(false, src4, dst4)
	if !ok || uid != 10123 || err != nil {
		t.Fatalf("got ok=%v uid=%d err=%v", ok, uid, err)
	}
	if got, want := s.last(), "tcp 172.19.0.1:40000 93.184.216.34:443"; got != want {
		t.Fatalf("request %q, want %q", got, want)
	}
}

func TestDenyInvalidUID(t *testing.T) {
	_, path := startServer(t, func(string) string { return "-1" })
	c := newChecker(path, nil)
	defer c.Close()
	if ok, uid, _ := c.Allow(true, src4, dst4); ok || uid != InvalidUID {
		t.Fatalf("flow owned by INVALID_UID allowed (uid=%d)", uid)
	}
}

func TestIPv6AndMappedFormatting(t *testing.T) {
	s, path := startServer(t, func(string) string { return "10001" })
	c := newChecker(path, nil)
	defer c.Close()
	c.Allow(true, src6, dst6)
	if got, want := s.last(), "udp [fdfe:dcba:9876::1]:40001 [2001:db8::1]:443"; got != want {
		t.Fatalf("request %q, want %q", got, want)
	}
	mapped := netip.AddrPortFrom(netip.AddrFrom16(netip.MustParseAddr("::ffff:10.0.0.5").As16()), 1234)
	c.Allow(false, mapped, dst4)
	if got, want := s.last(), "tcp 10.0.0.5:1234 93.184.216.34:443"; got != want {
		t.Fatalf("request %q, want %q (v4-mapped must be unmapped)", got, want)
	}
}

func TestDNSPlaceholderBypass(t *testing.T) {
	s, path := startServer(t, func(string) string { return "-1" })
	c := newChecker(path, nil)
	defer c.Close()
	for _, dst := range DefaultBypass {
		if ok, _, _ := c.Allow(true, src4, dst); !ok {
			t.Fatalf("DNS placeholder %s dropped", dst)
		}
	}
	if n := s.queries.Load(); n != 0 {
		t.Fatalf("bypass destinations queried %d times", n)
	}
	// Other port on the placeholder is NOT bypassed.
	if ok, _, _ := c.Allow(false, src4, netip.MustParseAddrPort("172.19.0.2:853")); ok {
		t.Fatal("172.19.0.2:853 should be checked and dropped")
	}
}

func TestCache(t *testing.T) {
	s, path := startServer(t, func(string) string { return "-1" })
	c := newChecker(path, func(cfg *Config) { cfg.CacheTTL = time.Minute })
	defer c.Close()
	for i := 0; i < 5; i++ {
		if ok, _, _ := c.Allow(true, src4, dst4); ok {
			t.Fatal("allowed")
		}
	}
	if n := s.queries.Load(); n != 1 {
		t.Fatalf("expected 1 query (cached), got %d", n)
	}
	// Different protocol / tuple → new query.
	c.Allow(false, src4, dst4)
	if n := s.queries.Load(); n != 2 {
		t.Fatalf("expected 2 queries, got %d", n)
	}
	// Expiry.
	base := time.Now()
	c.now = func() time.Time { return base.Add(2 * time.Minute) }
	c.Allow(true, src4, dst4)
	if n := s.queries.Load(); n != 3 {
		t.Fatalf("expected re-query after TTL, got %d", n)
	}
}

func TestConnectionReuse(t *testing.T) {
	s, path := startServer(t, func(string) string { return "10050" })
	c := newChecker(path, func(cfg *Config) { cfg.CacheTTL = -1 })
	defer c.Close()
	for i := 0; i < 20; i++ {
		c.Allow(false, netip.AddrPortFrom(src4.Addr(), uint16(30000+i)), dst4)
	}
	if n := s.queries.Load(); n != 20 {
		t.Fatalf("queries=%d", n)
	}
	if n := s.conns.Load(); n != 1 {
		t.Fatalf("sequential queries should reuse one connection, opened %d", n)
	}
}

func TestTimeoutStrictAndLenient(t *testing.T) {
	_, path := startServer(t, func(string) string { return "" }) // never answers
	for _, strict := range []bool{false, true} {
		c := newChecker(path, func(cfg *Config) {
			cfg.Strict = strict
			cfg.Timeout = 50 * time.Millisecond
		})
		start := time.Now()
		ok, _, err := c.Allow(false, src4, dst4)
		el := time.Since(start)
		c.Close()
		if err == nil {
			t.Fatal("expected a timeout error")
		}
		if ok == strict {
			t.Fatalf("strict=%v: ok=%v", strict, ok)
		}
		if el > 500*time.Millisecond {
			t.Fatalf("query took %s, timeout not honoured", el)
		}
	}
}

func TestNoServer(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing")
	lenient := newChecker(path, nil)
	if ok, _, err := lenient.Allow(false, src4, dst4); !ok || err == nil {
		t.Fatalf("lenient: ok=%v err=%v", ok, err)
	}
	strict := newChecker(path, func(cfg *Config) { cfg.Strict = true })
	if ok, _, _ := strict.Allow(false, src4, dst4); ok {
		t.Fatal("strict: flow allowed without an owner server")
	}
}

func TestErrAnswer(t *testing.T) {
	_, path := startServer(t, func(string) string { return "err" })
	strict := newChecker(path, func(cfg *Config) { cfg.Strict = true })
	defer strict.Close()
	if ok, _, err := strict.Allow(false, src4, dst4); ok || err == nil {
		t.Fatalf("strict err answer: ok=%v err=%v", ok, err)
	}
	lenient := newChecker(path, nil)
	defer lenient.Close()
	if ok, _, _ := lenient.Allow(false, src4, dst4); !ok {
		t.Fatal("lenient err answer should allow")
	}
}

func TestUnknownUIDAllowed(t *testing.T) {
	// API < 29: the server answers -2 ("unknown, allow").
	_, path := startServer(t, func(string) string { return "-2" })
	c := newChecker(path, func(cfg *Config) { cfg.Strict = true })
	defer c.Close()
	if ok, _, _ := c.Allow(false, src4, dst4); !ok {
		t.Fatal("-2 must allow")
	}
}

func TestConcurrent(t *testing.T) {
	s, path := startServer(t, func(req string) string {
		time.Sleep(2 * time.Millisecond)
		if strings.Contains(req, ":5") { // src ports 5xxxx → foreign
			return "-1"
		}
		return "10200"
	})
	c := newChecker(path, func(cfg *Config) { cfg.Timeout = 2 * time.Second; cfg.MaxConcurrent = 4 })
	defer c.Close()
	var wg sync.WaitGroup
	var bad atomic.Int32
	for i := 0; i < 64; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			port := uint16(40000 + i)
			if i%2 == 1 {
				port = uint16(50000 + i)
			}
			ok, _, err := c.Allow(false, netip.AddrPortFrom(src4.Addr(), port), dst4)
			if err != nil || ok != (i%2 == 0) {
				bad.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if bad.Load() != 0 {
		t.Fatalf("%d wrong verdicts", bad.Load())
	}
	if n := s.conns.Load(); n > 4 {
		t.Fatalf("opened %d connections, MaxConcurrent=4", n)
	}
}

// --- Dialer wrapper ---

type fakeDialer struct{ tcp, udp atomic.Int32 }

func (f *fakeDialer) DialContext(context.Context, *M.Metadata) (net.Conn, error) {
	f.tcp.Add(1)
	a, _ := net.Pipe()
	return a, nil
}

func (f *fakeDialer) DialUDP(*M.Metadata) (net.PacketConn, error) {
	f.udp.Add(1)
	return nil, errors.New("dialed")
}

func TestDialerWrapper(t *testing.T) {
	_, path := startServer(t, func(req string) string {
		if strings.Contains(req, ":666 ") {
			return "-1"
		}
		return "10300"
	})
	inner := &fakeDialer{}
	d := &Dialer{Dialer: inner, C: newChecker(path, nil)}

	md := func(network M.Network, srcPort uint16) *M.Metadata {
		return &M.Metadata{Network: network, SrcIP: src4.Addr(), SrcPort: srcPort, DstIP: dst4.Addr(), DstPort: dst4.Port()}
	}
	if _, err := d.DialContext(context.Background(), md(M.TCP, 666)); !errors.Is(err, ErrNotOwned) {
		t.Fatalf("foreign TCP: err=%v", err)
	}
	if _, err := d.DialUDP(md(M.UDP, 666)); !errors.Is(err, ErrNotOwned) {
		t.Fatalf("foreign UDP: err=%v", err)
	}
	if inner.tcp.Load() != 0 || inner.udp.Load() != 0 {
		t.Fatal("foreign flow reached the proxy dialer")
	}
	if c, err := d.DialContext(context.Background(), md(M.TCP, 1000)); err != nil {
		t.Fatalf("own TCP: %v", err)
	} else {
		c.Close()
	}
	d.DialUDP(md(M.UDP, 1000))
	if inner.tcp.Load() != 1 || inner.udp.Load() != 1 {
		t.Fatalf("own flows not dialed: tcp=%d udp=%d", inner.tcp.Load(), inner.udp.Load())
	}
}

func TestParseBypass(t *testing.T) {
	got, err := ParseBypass(" 172.19.0.2:53 , [fdfe:dcba:9876::2]:53,")
	if err != nil || len(got) != 2 || got[1] != DefaultBypass[1] {
		t.Fatalf("got %v err %v", got, err)
	}
	if _, err := ParseBypass("1.2.3.4"); err == nil {
		t.Fatal("missing port accepted")
	}
}

func TestSkipUDP(t *testing.T) {
	s, path := startServer(t, func(string) string { return "-1" })
	c := newChecker(path, func(cfg *Config) { cfg.SkipUDP = true })
	defer c.Close()
	if ok, _, _ := c.Allow(true, src4, dst4); !ok {
		t.Fatal("UDP dropped with SkipUDP")
	}
	if ok, _, _ := c.Allow(false, src4, dst4); ok {
		t.Fatal("TCP must still be checked")
	}
	if n := s.queries.Load(); n != 1 {
		t.Fatalf("queries=%d", n)
	}
}
