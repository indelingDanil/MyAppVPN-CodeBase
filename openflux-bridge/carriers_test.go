// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/share"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/manager"
	"github.com/p1neappleXpress/OpenFlux/transport/phpbox"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
)

const testSecret = "0123456789abcdef-key"

func mustLink(t *testing.T, c share.Config) string {
	t.Helper()
	l, err := share.Encode(c)
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func TestNormTransport(t *testing.T) {
	for in, want := range map[string]string{
		"": "v2", "v2": "v2", "mailru": "mailru", "Mail.ru": "mailru", "cups": "cups",
		"cupsonline": "cups", "session": "session", "yandex": "", "bogus": "",
	} {
		if got := normTransport(in); got != want {
			t.Errorf("normTransport(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestPlanCarrierStream(t *testing.T) {
	p, err := planCarrier("mailru", " https://cloud.mail.ru/public/abc/def ", "")
	if err != nil || !p.stream || p.typ != "mailru" || p.link != "https://cloud.mail.ru/public/abc/def" || p.label != "mailru" {
		t.Fatalf("mailru: %+v %v", p, err)
	}
	if h := p.carrierHost(); h != "cloud.mail.ru" {
		t.Fatalf("host %q", h)
	}
	p, err = planCarrier("cups", "https://cups.online/room/x", "")
	if err != nil || p.typ != "cupsonline" {
		t.Fatalf("cups: %+v %v", p, err)
	}
	if h := p.carrierHost(); h != "interview.cups.online" {
		t.Fatalf("cups host %q", h)
	}
	if p, err := planCarrier("cups", "c29tZS1yb29tLWxpc3Q", ""); err != nil || p.link != "c29tZS1yb29tLWxpc3Q" {
		t.Fatalf("cups room list: %+v %v", p, err)
	}
	for _, bad := range []struct{ tr, link string }{
		{"mailru", ""},
		{"mailru", "openflux://v1/xxx"},
		{"mailru", "not a url"},
		{"yandex", "https://disk.yandex.ru/x"},
		{"session", ""},
		{"session", "https://cloud.mail.ru/public/a"},
	} {
		if _, err := planCarrier(bad.tr, bad.link, ""); err == nil {
			t.Errorf("planCarrier(%q, %q) accepted", bad.tr, bad.link)
		}
	}
}

func TestPlanCarrierSessionLinks(t *testing.T) {
	// an encrypted Session to a node: a document plus direct as backup
	link := mustLink(t, share.Config{Negotiate: true, Secret: testSecret, Transports: []share.Transport{
		{Type: "yandex", URL: "https://disk.yandex.ru/edit/d/abc", Priority: 100},
		{Type: "direct", Dial: "203.0.113.5:8443", Priority: 50},
		{Type: "yandex", URL: "https://disk.yandex.ru/edit/d/def", Priority: 10},
	}})
	p, err := planCarrier("session", link, "")
	if err != nil || p.stream || p.secret != testSecret || p.label != "session" {
		t.Fatalf("session: %+v %v", p, err)
	}
	specs := p.sessionSpecs()
	if len(specs) != 3 || specs[0].name != "yandex" || specs[1].name != "direct" || specs[2].name != "yandex-2" {
		t.Fatalf("names: %+v", specs)
	}
	if specs[1].url != "" || specs[1].params["dial"] != "203.0.113.5:8443" {
		t.Fatalf("direct spec: %+v", specs[1])
	}
	if h := p.carrierHost(); h != "disk.yandex.ru" {
		t.Fatalf("host %q", h)
	}

	// -secret overrides the link's key; a short one is refused
	if p, err := planCarrier("session", link, "another-secret-of-16+"); err != nil || p.secret != "another-secret-of-16+" {
		t.Fatalf("override: %+v %v", p, err)
	}
	if _, err := planCarrier("session", link, "short"); err == nil {
		t.Fatal("short -secret accepted")
	}

	// a classic link without a key: one carrier, unencrypted
	plain := mustLink(t, share.Config{Transports: []share.Transport{{Type: "mailru", URL: "https://cloud.mail.ru/public/a/b"}}})
	if p, err := planCarrier("session", plain, ""); err != nil || p.stream || p.secret != "" {
		t.Fatalf("classic: %+v %v", p, err)
	}

	// a stream-mode link (PHP / Node stream exit) runs stream mode
	stream := mustLink(t, share.Config{Mode: share.ModeStream, Transports: []share.Transport{{Type: "mailru", URL: "https://cloud.mail.ru/public/s/t"}}})
	if p, err := planCarrier("session", stream, ""); err != nil || !p.stream || p.typ != "mailru" || p.link != "https://cloud.mail.ru/public/s/t" {
		t.Fatalf("stream link: %+v %v", p, err)
	}

	// damaged link
	if _, err := planCarrier("session", link[:len(link)-6], ""); err == nil {
		t.Fatal("cut link accepted")
	}
}

func TestStatusTracker(t *testing.T) {
	t0 := time.Unix(1000, 0)
	s := newStatusTracker(t0, 10*time.Second)
	s.last = "connecting" // runCarrierMode printed it already
	steps := []struct {
		conn   bool
		active string
		at     time.Duration
		want   string
	}{
		{false, "", 1 * time.Second, ""},
		{false, "", 11 * time.Second, "OFSTATUS error"},
		{false, "", 12 * time.Second, ""},
		{true, "yandex", 13 * time.Second, "OFSTATUS connected|OFNOTE carrier yandex"},
		{true, "yandex", 14 * time.Second, ""},
		{true, "direct", 15 * time.Second, "OFNOTE carrier direct"},
		{false, "", 16 * time.Second, "OFSTATUS connecting"},
		{false, "", 25 * time.Second, ""},
		{false, "", 26 * time.Second, "OFSTATUS error"},
	}
	for i, st := range steps {
		got := strings.Join(s.step(st.conn, st.active, t0.Add(st.at)), "|")
		if got != st.want {
			t.Fatalf("step %d: %q, want %q", i, got, st.want)
		}
	}
}

// fakeExit is a carrier with a phpbox stream exit on the far end that echoes
// every stream.
type fakeExit struct {
	mu      sync.Mutex
	recv    func([]byte)
	buf     []byte
	opened  []string
	started atomic.Bool
	notify  func(error, string, string, string, string)
	applied chan map[string]string
	once    sync.Once
	q       chan []byte
}

func (f *fakeExit) Start() error            { f.started.Store(true); return nil }
func (f *fakeExit) Stop() error             { f.started.Store(false); return nil }
func (f *fakeExit) Receive(cb func([]byte)) { f.mu.Lock(); f.recv = cb; f.mu.Unlock() }
func (f *fakeExit) IsConnected() bool       { return f.started.Load() }
func (f *fakeExit) Stats() transport.TransportStats {
	return transport.TransportStats{}
}
func (f *fakeExit) SetErrorNotifier(n func(error, string, string, string, string)) { f.notify = n }
func (f *fakeExit) FetchCookies() (map[string]string, error)                       { return nil, nil }
func (f *fakeExit) ApplyCookies(jar map[string]string) error {
	if f.applied != nil {
		f.applied <- jar
	}
	return nil
}

func (f *fakeExit) Send(p []byte) error {
	f.mu.Lock()
	f.buf = append(f.buf, p...)
	var out []byte
	for {
		fr, rest, ok := phpbox.TakeFrame(f.buf)
		if !ok {
			break
		}
		f.buf = rest
		switch fr.Type {
		case phpbox.FrameOpen:
			target, _, _ := strings.Cut(string(fr.Payload), "\x00")
			f.opened = append(f.opened, target)
			out = phpbox.Encode(out, phpbox.Frame{Type: phpbox.FrameOpenOK, StreamID: fr.StreamID})
		case phpbox.FrameData:
			out = phpbox.Encode(out, phpbox.Frame{Type: phpbox.FrameData, StreamID: fr.StreamID, Payload: append([]byte(nil), fr.Payload...)})
		case phpbox.FrameClose:
			out = phpbox.Encode(out, phpbox.Frame{Type: phpbox.FrameClose, StreamID: fr.StreamID})
		}
	}
	if len(out) > 0 {
		f.once.Do(func() {
			f.q = make(chan []byte, 1<<16)
			go func() { // one goroutine: replies arrive in order
				for p := range f.q {
					f.mu.Lock()
					cb := f.recv
					f.mu.Unlock()
					if cb != nil {
						cb(p)
					}
				}
			}()
		})
		f.q <- out // under f.mu, so in the order the frames came
	}
	f.mu.Unlock()
	return nil
}

func freeAddr(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	a := ln.Addr().String()
	ln.Close()
	return a
}

// socksConnect does a SOCKS5 user/pass handshake and CONNECT host:port.
func socksConnect(t *testing.T, addr, user, pass, host string, port int) (net.Conn, error) {
	t.Helper()
	c, err := net.DialTimeout("tcp", addr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	b := []byte{5, 1, 2}
	if _, err := c.Write(b); err != nil {
		return nil, err
	}
	r := make([]byte, 2)
	if _, err := io.ReadFull(c, r); err != nil || r[1] != 2 {
		c.Close()
		return nil, errors.New("method refused")
	}
	auth := append([]byte{1, byte(len(user))}, user...)
	auth = append(append(auth, byte(len(pass))), pass...)
	c.Write(auth)
	if _, err := io.ReadFull(c, r); err != nil || r[1] != 0 {
		c.Close()
		return nil, errors.New("auth refused")
	}
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, host...)
	req = binary.BigEndian.AppendUint16(req, uint16(port))
	c.Write(req)
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil || rep[1] != 0 {
		c.Close()
		return nil, errors.New("connect refused")
	}
	return c, nil
}

func TestStreamModeThroughFakeCarrier(t *testing.T) {
	fx := &fakeExit{}
	old := newRawCarrier
	newRawCarrier = func(typ, link string, _ map[string]interface{}) (transport.Transport, error) {
		if typ != "mailru" || link != "https://cloud.mail.ru/public/x/y" {
			t.Errorf("carrier %s %s", typ, link)
		}
		return fx, nil
	}
	defer func() { newRawCarrier = old }()

	p, err := planCarrier("mailru", "https://cloud.mail.ru/public/x/y", "")
	if err != nil {
		t.Fatal(err)
	}
	addr := freeAddr(t)
	var out bytes.Buffer
	rc, err := startCarrier(p, addr, "u", "secretpass", newChecks("", &out))
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Stop()
	if !rc.Connected() {
		t.Fatal("not connected")
	}
	if _, err := socksConnect(t, addr, "u", "wrong", "example.com", 443); err == nil {
		t.Fatal("wrong password accepted")
	}
	c, err := socksConnect(t, addr, "u", "secretpass", "example.com", 443)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	msg := bytes.Repeat([]byte("hello through mail.ru "), 3000)
	go c.Write(msg)
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatal("echo mismatch")
	}
	fx.mu.Lock()
	opened := append([]string(nil), fx.opened...)
	fx.mu.Unlock()
	if len(opened) != 1 || opened[0] != "example.com:443" {
		t.Fatalf("opened %v", opened)
	}
}

func TestCaptchaNoteAndCookies(t *testing.T) {
	fx := &fakeExit{applied: make(chan map[string]string, 1)}
	var out bytes.Buffer
	ck := newChecks("", &out)
	ck.attachRaw("mailru", "https://cloud.mail.ru/public/x/y", fx)
	if err := ck.submit("a=1"); err == nil {
		t.Fatal("cookies accepted with no check pending")
	}
	fx.notify(errors.New("captcha"), "mailru", "https://example.org/check?x=1", "", "smartcaptcha")
	if got := out.String(); got != "OFNOTE captcha https://example.org/check?x=1\n" {
		t.Fatalf("note %q", got)
	}
	if !ck.asked.Load() {
		t.Fatal("asked not set")
	}
	ck.readCommands(strings.NewReader("junk\nOFCOOKIES a=1; b=two=2\n"))
	select {
	case jar := <-fx.applied:
		if jar["a"] != "1" || jar["b"] != "two=2" || len(jar) != 2 {
			t.Fatalf("jar %v", jar)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cookies not applied")
	}
}

func TestCarrierDiag(t *testing.T) {
	oldWait := diagCarrierWait
	diagCarrierWait = 2 * time.Second
	defer func() { diagCarrierWait = oldWait }()
	old := newRawCarrier
	defer func() { newRawCarrier = old }()

	newRawCarrier = func(string, string, map[string]interface{}) (transport.Transport, error) { return &fakeExit{}, nil }
	r := runCarrierDiag("mailru", "https://127.0.0.1/public/x", "")
	if r.Carrier == nil || !r.Carrier.Started || !r.Carrier.Connected || r.Summary != "ok" || r.Host != "127.0.0.1" {
		t.Fatalf("connected: %+v %+v", r, r.Carrier)
	}

	newRawCarrier = func(string, string, map[string]interface{}) (transport.Transport, error) {
		return &neverConnects{}, nil
	}
	r = runCarrierDiag("mailru", "https://127.0.0.1/public/z", "")
	if r.Carrier.Connected || !r.Carrier.Started || r.Carrier.Transport != "mailru" {
		t.Fatalf("timeout: %+v", r.Carrier)
	}
	if r.Summary != "carrier_timeout" && r.Summary != "tcp_failed" {
		t.Fatalf("summary %q", r.Summary)
	}

	newRawCarrier = func(string, string, map[string]interface{}) (transport.Transport, error) {
		return nil, errors.New("boom")
	}
	r = runCarrierDiag("mailru", "https://127.0.0.1/public/x", "")
	if r.Carrier.Started || r.Carrier.Err == "" {
		t.Fatalf("failed: %+v", r.Carrier)
	}
	if r = runCarrierDiag("session", "openflux://v1/!!!", ""); r.Summary != "bad_link" {
		t.Fatalf("bad link: %q", r.Summary)
	}
}

type neverConnects struct{ fakeExit }

func (*neverConnects) IsConnected() bool { return false }

// pipeEnd is one end of an in-memory carrier pair (ordered, asynchronous).
type pipeEnd struct {
	mu   sync.Mutex
	cb   func([]byte)
	peer *pipeEnd
	q    chan []byte
	up   atomic.Bool
}

func newPipePair() (*pipeEnd, *pipeEnd) {
	a := &pipeEnd{q: make(chan []byte, 4096)}
	b := &pipeEnd{q: make(chan []byte, 4096)}
	a.peer, b.peer = b, a
	for _, e := range []*pipeEnd{a, b} {
		go func(e *pipeEnd) {
			for p := range e.q {
				e.mu.Lock()
				cb := e.cb
				e.mu.Unlock()
				if cb != nil {
					cb(p)
				}
			}
		}(e)
	}
	return a, b
}

func (p *pipeEnd) Start() error                    { p.up.Store(true); return nil }
func (p *pipeEnd) Stop() error                     { p.up.Store(false); return nil }
func (p *pipeEnd) IsConnected() bool               { return p.up.Load() }
func (p *pipeEnd) Stats() transport.TransportStats { return transport.TransportStats{} }
func (p *pipeEnd) Receive(cb func([]byte))         { p.mu.Lock(); p.cb = cb; p.mu.Unlock() }
func (p *pipeEnd) Send(b []byte) error {
	p.peer.q <- append([]byte(nil), b...)
	return nil
}

// TestSessionLinkEndToEnd runs a share link against a core exit node over an
// in-memory carrier: SOCKS5 → TCPTunnel → encrypted Session → exit's stack →
// a local echo server.
func TestSessionLinkEndToEnd(t *testing.T) {
	// gVisor drops loopback destinations ("martian"), so the echo server sits on
	// this machine's LAN address.
	lanIP := localLANIPv4()
	if lanIP == "" {
		t.Skip("no non-loopback IPv4 address")
	}
	echo, err := net.Listen("tcp", net.JoinHostPort(lanIP, "0"))
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()

	cfg := share.Config{Negotiate: true, Secret: testSecret, Transports: []share.Transport{
		{Type: "yandex", URL: "https://disk.yandex.ru/edit/d/e2e-test", Priority: 100},
	}}
	link := mustLink(t, cfg)
	cliEnd, exitEnd := newPipePair()

	// exit node (the core, as their VPS node runs it)
	ctx := share.ContextOf(cfg)
	esess, err := transport.NewSession(transport.PeerParameters{
		Capabilities:  transport.CapabilityIPv4 | transport.CapabilityTCP | transport.CapabilityUDP,
		MaxPacketSize: transport.MaxNegotiatedPacket,
	}, true)
	if err != nil {
		t.Fatal(err)
	}
	em := manager.New(esess, nil, testSecret, ctx)
	if err := esess.AddTransport("yandex", exitEnd, testSecret, ctx, 100); err != nil {
		t.Fatal(err)
	}
	if err := em.Add("yandex", "yandex", exitEnd, 100, nil); err != nil {
		t.Fatal(err)
	}
	esess.SetControlHandler(em.DispatchControl)
	if err := em.Start(); err != nil {
		t.Fatal(err)
	}
	etun := tunnel.NewTCPTunnel(em, true)
	defer etun.Close()
	defer em.Stop()

	old := newRawCarrier
	newRawCarrier = func(typ, link string, _ map[string]interface{}) (transport.Transport, error) {
		if typ != "yandex" || link != "https://disk.yandex.ru/edit/d/e2e-test" {
			t.Errorf("carrier %s %s", typ, link)
		}
		return cliEnd, nil
	}
	defer func() { newRawCarrier = old }()

	p, err := planCarrier("session", link, "")
	if err != nil {
		t.Fatal(err)
	}
	addr := freeAddr(t)
	rc, err := startCarrier(p, addr, "u", "pw", newChecks("", io.Discard))
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Stop()
	deadline := time.Now().Add(15 * time.Second)
	for !rc.Connected() && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
	if !rc.Connected() {
		t.Fatal("session did not connect")
	}
	if a := rc.Active(); a != "yandex" {
		t.Errorf("active carrier %q", a)
	}
	_, port, _ := net.SplitHostPort(echo.Addr().String())
	var n int
	for _, ch := range port {
		n = n*10 + int(ch-'0')
	}
	c, err := socksConnect(t, addr, "u", "pw", lanIP, n)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	msg := bytes.Repeat([]byte("session e2e "), 5000)
	go c.Write(msg)
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatal("echo mismatch")
	}
}

func localLANIPv4() string {
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && !n.IP.IsLoopback() && !n.IP.IsLinkLocalUnicast() {
			return n.IP.String()
		}
	}
	return ""
}

// A script carrier's own page (inline html or its loopback server) cannot be
// opened by the app: the line still says "captcha", with "-" for the address.
func TestPromptOwnPageNote(t *testing.T) {
	for _, tc := range []struct{ url, html string }{
		{"", "<html></html>"},
		{"http://127.0.0.1:8123/setup", ""},
	} {
		var out bytes.Buffer
		ck := newChecks("", &out)
		ck.pending("script", tc.url, tc.html, "setup", "", nil)
		if got := out.String(); got != "OFNOTE captcha -\n" {
			t.Fatalf("%q/%q: note %q", tc.url, tc.html, got)
		}
	}
}
