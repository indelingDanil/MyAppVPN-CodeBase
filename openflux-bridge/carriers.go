// SPDX-License-Identifier: GPL-3.0-or-later

package main

// Carriers other than our own v2 HTTP exit, built from the OpenFlux core
// (github.com/p1neappleXpress/OpenFlux, GPL-3.0; see NOTICE):
//
//	-transport v2       (default) our HTTP exit, main.go — unchanged.
//	-transport mailru   stream mode: the phpbox stream mux rides a Mail.ru
//	                    document (-link = its public link) to a stream exit.
//	-transport cups     the same over a cups.online room (-link = room address).
//	-transport session  an openflux://v1/ share link (-link): an encrypted
//	                    Session to any OpenFlux node (their VPS node, a phone
//	                    exit), or a stream exit when the link says mode=stream.
//	                    -secret supplies/overrides the link's key.
//
// Every mode serves the same local SOCKS5 (-listen, -user/-pass) Xray dials.
// Status for the app (stdout):
//
//	OFNOTE transport <name>   once, at start
//	OFSTATUS connecting       carrier not (yet / any more) connected
//	OFSTATUS connected        carrier joined its document/room (session: handshake done)
//	OFSTATUS error            start failed, or still not connected after carrierConnectWait
//	OFNOTE carrier <type>     session: the carrier traffic currently rides (on change)
//	OFNOTE captcha <url>      a carrier needs a browser check; answer with a stdin
//	                          line "OFCOOKIES a=1; b=2" (kept in -cookies FILE)

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/share"
	"github.com/p1neappleXpress/OpenFlux/socks5"
	"github.com/p1neappleXpress/OpenFlux/streamproxy"
	"github.com/p1neappleXpress/OpenFlux/transport"
	"github.com/p1neappleXpress/OpenFlux/transport/manager"
	"github.com/p1neappleXpress/OpenFlux/transport/phpbox"
	"github.com/p1neappleXpress/OpenFlux/transport/registry"
	"github.com/p1neappleXpress/OpenFlux/transport/script"
	"github.com/p1neappleXpress/OpenFlux/tunnel"
	"github.com/p1neappleXpress/OpenFlux/utils"
)

var (
	flagTransport = flag.String("transport", "v2", "carrier to the exit: v2 (our HTTP exit), mailru, cups (stream mode), session (openflux:// link)")
	flagLink      = flag.String("link", "", "mailru: document public link; cups: room address; session: openflux://v1/... share link")
	flagSecret    = flag.String("secret", "", "session: encryption key (supplies or overrides the link's)")
	flagCookies   = flag.String("cookies", "", "file keeping cookies passed for captcha checks (OFCOOKIES lines on stdin)")
)

// Timings (vars so tests can shrink them).
var (
	carrierConnectWait = 60 * time.Second // connecting → "OFSTATUS error" (keeps trying)
	carrierPoll        = 500 * time.Millisecond
)

// carrierPlan is what -transport/-link/-secret resolve to.
type carrierPlan struct {
	label  string // OFNOTE transport <label>
	stream bool
	// stream mode: one carrier (registry type) and its link
	typ, link string
	// session: the decoded share link and the key to use
	cfg    share.Config
	secret string
}

// normTransport maps the flag to a canonical name; "" = unknown.
func normTransport(s string) string {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "v2", "direct", "http":
		return "v2"
	case "mailru", "mail.ru":
		return "mailru"
	case "cups", "cupsonline", "cups.online":
		return "cups"
	case "session", "link", "openflux":
		return "session"
	}
	return ""
}

// planCarrier validates the flags of a non-v2 transport.
func planCarrier(transportName, link, secret string) (*carrierPlan, error) {
	link = strings.TrimSpace(link)
	switch normTransport(transportName) {
	case "mailru", "cups":
		typ := "mailru"
		if normTransport(transportName) == "cups" {
			typ = "cupsonline"
		}
		if link == "" {
			return nil, fmt.Errorf("-transport %s needs -link", transportName)
		}
		if strings.HasPrefix(link, "openflux://") {
			return nil, fmt.Errorf("-transport %s takes the document/room link; use -transport session for openflux:// links", transportName)
		}
		// mailru: the document's public link; cups: the exit's base64 room list
		if u, err := url.Parse(link); typ == "mailru" && (err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "") {
			return nil, fmt.Errorf("-link must be the document's http(s) address")
		}
		return &carrierPlan{label: typ, stream: true, typ: typ, link: link}, nil
	case "session":
		if link == "" {
			return nil, errors.New("-transport session needs -link openflux://v1/...")
		}
		cfg, err := share.Decode(link)
		if err != nil {
			return nil, err
		}
		if cfg.Mode == share.ModeStream {
			t := cfg.Transports[0]
			return &carrierPlan{label: t.Type, stream: true, typ: t.Type, link: t.URL}, nil
		}
		if s := strings.TrimSpace(secret); s != "" {
			cfg.Secret = s
		}
		if cfg.Secret != "" && utils.SecretChars(cfg.Secret) < utils.MinSecretChars {
			return nil, fmt.Errorf("the key must be at least %d characters", utils.MinSecretChars)
		}
		if cfg.Secret == "" && (cfg.Negotiate || len(cfg.Transports) > 1) {
			return nil, errors.New("this link needs an encryption key (-secret)")
		}
		for _, t := range cfg.Transports {
			if t.Type == "direct" && t.Dial == "" {
				return nil, errors.New("direct carrier without an address")
			}
		}
		return &carrierPlan{label: "session", cfg: cfg, secret: cfg.Secret}, nil
	case "v2":
		return nil, errors.New("v2 is not a core carrier")
	}
	return nil, fmt.Errorf("unknown -transport %q (v2, mailru, cups, session)", transportName)
}

// carrierHost is the service host the plan talks to (diagnostics).
func (p *carrierPlan) carrierHost() string {
	typ, raw := p.typ, p.link
	if !p.stream && len(p.cfg.Transports) > 0 {
		t := p.cfg.Transports[0]
		typ, raw = t.Type, t.URL
		if t.Type == "direct" {
			raw = "tcp://" + t.Dial
		}
	}
	if typ == "cupsonline" {
		return "interview.cups.online" // the link is a room list, not an address
	}
	if u, err := url.Parse(raw); err == nil {
		return u.Hostname()
	}
	return ""
}

// sessionTypes lists the carrier types of a session plan, by name.
func (p *carrierPlan) sessionSpecs() []sessionSpec {
	specs := make([]sessionSpec, 0, len(p.cfg.Transports))
	seen := map[string]int{}
	for _, t := range p.cfg.Transports {
		name := t.Name
		if name == "" {
			seen[t.Type]++
			name = t.Type
			if n := seen[t.Type]; n > 1 {
				name = fmt.Sprintf("%s-%d", t.Type, n)
			}
		}
		s := sessionSpec{name: name, typ: t.Type, url: t.URL, priority: t.Priority}
		if t.Type == "direct" {
			s.url = ""
			s.params = map[string]interface{}{"dial": t.Dial}
		}
		specs = append(specs, s)
	}
	return specs
}

type sessionSpec struct {
	name, typ, url string
	priority       int
	params         map[string]interface{}
}

// newRawCarrier builds one core carrier (a var so tests can fake it).
var newRawCarrier = func(typ, link string, params map[string]interface{}) (transport.Transport, error) {
	return registry.New(typ, link, params, registry.Options{
		Base:         transport.DefaultConfig(),
		StrictDirect: true,
	})
}

// ── captcha / cookies ───────────────────────────────────────────────────────

// checks routes a carrier's browser check to the app (OFNOTE captcha) and
// the cookies the app answers with (stdin "OFCOOKIES ...") back to it.
type checks struct {
	mu    sync.Mutex
	store *transport.CookieStore
	key   string                        // store key of the pending check
	apply func(map[string]string) error // hands cookies to the live carrier
	out   io.Writer
	asked atomic.Bool // a check was requested (diagnostics)
}

func newChecks(path string, out io.Writer) *checks {
	c := &checks{out: out}
	if path != "" {
		s, err := transport.NewCookieStore(path)
		if err != nil {
			log.Printf("cookies: %v", err)
		} else {
			c.store = s
		}
	}
	return c
}

func (c *checks) pending(name, rawURL, html, reason, key string, apply func(map[string]string) error) {
	log.Printf("%s: browser check needed (%s)", name, reason)
	c.mu.Lock()
	c.key, c.apply = key, apply
	c.asked.Store(true)
	c.mu.Unlock()
	if script.IsOwnPage(rawURL, html) {
		rawURL = "" // the script's own page (inline html / its loopback server): not supported here
	}
	if rawURL == "" {
		rawURL = "-"
	}
	fmt.Fprintf(c.out, "OFNOTE captcha %s\n", strings.ReplaceAll(rawURL, " ", "%20"))
}

// submit takes a Cookie header ("a=1; b=2"), keeps it and applies it.
func (c *checks) submit(header string) error {
	jar := map[string]string{}
	for _, part := range strings.Split(header, ";") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if ok && k != "" {
			jar[k] = v
		}
	}
	if len(jar) == 0 {
		return errors.New("no cookies")
	}
	c.mu.Lock()
	store, key, apply := c.store, c.key, c.apply
	c.mu.Unlock()
	if store != nil && key != "" {
		if err := store.Save(key, jar); err != nil {
			log.Printf("cookies: save: %v", err)
		}
	}
	if apply == nil {
		return errors.New("no check pending")
	}
	go func() { // ApplyCookies may sleep through a reconnect backoff
		if err := apply(jar); err != nil {
			log.Printf("cookies: apply: %v", err)
		}
	}()
	return nil
}

// readCommands serves "OFCOOKIES <header>" lines from the app (stdin).
func (c *checks) readCommands(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if h, ok := strings.CutPrefix(line, "OFCOOKIES "); ok {
			if err := c.submit(h); err != nil {
				log.Printf("OFCOOKIES: %v", err)
			}
		}
	}
}

// attachRaw wires a single raw carrier (stream mode / classic) into checks
// and replays its saved cookies.
func (c *checks) attachRaw(typ, link string, raw transport.Transport) {
	key := typ + " " + link
	var apply func(map[string]string) error
	if x, ok := raw.(transport.CookieExchanger); ok {
		apply = x.ApplyCookies
		if c.store != nil {
			if saved := c.store.Load(key); len(saved) > 0 {
				_ = x.ApplyCookies(saved)
			}
		}
	}
	if n, ok := raw.(transport.ErrorNotifier); ok {
		n.SetErrorNotifier(func(_ error, name, rawURL, html, reason string) {
			if name == "" {
				name = typ
			}
			c.pending(name, rawURL, html, reason, key, apply)
		})
	}
}

// ── running carriers ────────────────────────────────────────────────────────

// runningCarrier is a started non-v2 carrier behind the local SOCKS5.
type runningCarrier interface {
	Connected() bool
	Active() string // session: carrier type traffic rides now ("" = n/a)
	Stop()
}

type streamRun struct{ p *streamproxy.Proxy }

func (s streamRun) Connected() bool { return s.p.Connected() }
func (s streamRun) Active() string  { return "" }
func (s streamRun) Stop()           { s.p.Stop() }

// startStream runs stream mode: the phpbox mux over one carrier, behind the
// core's SOCKS5 server (with our -user/-pass).
func startStream(p *carrierPlan, listen, user, pass string, ck *checks) (runningCarrier, error) {
	raw, err := newRawCarrier(p.typ, p.link, nil)
	if err != nil {
		return nil, err
	}
	carrier, ok := raw.(phpbox.Carrier)
	if !ok {
		return nil, fmt.Errorf("%s cannot carry a stream", p.typ)
	}
	ck.attachRaw(p.typ, p.link, raw)
	px, err := streamproxy.Start(streamproxy.Options{
		Carrier: carrier, Socks: listen, Username: user, Password: pass, Label: p.typ,
	})
	if err != nil {
		return nil, err
	}
	return streamRun{px}, nil
}

type sessionRun struct {
	trans  transport.Transport
	sess   *transport.Session // nil: classic without a key
	types  map[string]string
	tun    *tunnel.TCPTunnel
	server *socks5.SOCKS5Server
	once   sync.Once
}

func (s *sessionRun) Connected() bool { return s.trans.IsConnected() }
func (s *sessionRun) Active() string {
	if s.sess == nil {
		return ""
	}
	name := s.sess.ActiveTransport()
	if t := s.types[name]; t != "" {
		return t
	}
	return name
}
func (s *sessionRun) Stop() {
	s.once.Do(func() {
		_ = s.server.Close()
		s.tun.Close()
		_ = s.trans.Stop()
	})
}

// buildSession assembles the client side of an OpenFlux Session for a share
// link, the way the core's mobile bridge does (mobile/session.go): a Session
// with every carrier of the link, a Manager for failover and cookies, the
// link's encryption context. A one-carrier link falls back to the classic
// layering while the node does not answer the handshake. A classic link
// without a key runs the carrier bare under the codec.
func buildSession(p *carrierPlan, ck *checks) (transport.Transport, *transport.Session, map[string]string, error) {
	specs := p.sessionSpecs()
	types := map[string]string{}
	for _, s := range specs {
		types[s.name] = s.typ
	}
	if p.secret == "" { // classic, unencrypted (Validate allowed it: one carrier, no session)
		s := specs[0]
		raw, err := newRawCarrier(s.typ, s.url, s.params)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("%s: %w", s.name, err)
		}
		ck.attachRaw(s.typ, s.url, raw)
		log.Printf("session: classic %s without encryption (the link has no key)", s.typ)
		return transport.NewCodecTransport(raw, p.cfg.Codec, true), nil, types, nil
	}

	// Like mobile (ShareSessionSpecs → parseSessionSpecs): the link's context
	// is pinned first (share.ContextOf: the link's explicit context, else the
	// fixed placeholder) and passed as the explicit one, so the primary is
	// that pinned string and the alternates are the legacy contexts older
	// nodes derived from carrier URLs/names.
	sources := make([]transport.ContextSource, len(specs))
	for i, s := range specs {
		sources[i] = transport.ContextSource{Type: s.typ, URL: s.url, Priority: s.priority}
	}
	context, alternates := transport.KDFContexts(share.ContextOf(p.cfg), "", sources)
	sess, err := transport.NewSession(transport.PeerParameters{
		Capabilities:  transport.CapabilityIPv4 | transport.CapabilityTCP | transport.CapabilityUDP | transport.CapabilityICMPErrors,
		MaxPacketSize: transport.MaxNegotiatedPacket,
	}, false)
	if err != nil {
		return nil, nil, nil, err
	}
	if len(specs) == 1 {
		codec := p.cfg.Codec
		if codec == "" {
			codec = transport.CodecBatched
		}
		sess.SetClassic(codec)
	}
	sess.SetAlternateContexts(alternates)
	log.Printf("session: context sha256 %s (%d alternates)", utils.Sha256Short([]byte(context)), len(alternates))

	m := manager.New(sess, nil, p.secret, context)
	keys := map[string]string{}
	for _, s := range specs {
		raw, err := newRawCarrier(s.typ, s.url, s.params)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("%s: %w", s.name, err)
		}
		if err := sess.AddTransport(s.name, raw, p.secret, context, s.priority); err != nil {
			return nil, nil, nil, err
		}
		provider, _ := raw.(manager.CookieProvider)
		if err := m.Add(s.name, s.typ, raw, s.priority, provider); err != nil {
			return nil, nil, nil, err
		}
		if s.typ != "direct" && s.typ != "oneme" {
			m.SetURL(s.name, s.url)
		}
		if provider != nil {
			keys[s.name] = s.typ + " " + s.url
		}
		log.Printf("session: carrier %s (%s), priority %d", s.name, s.typ, s.priority)
	}
	sess.SetControlHandler(m.DispatchControl)
	if ck.store != nil {
		for name, key := range keys {
			if err := m.UseCookieStore(ck.store, name, key); err != nil {
				log.Printf("session: %s: saved cookies not applied: %v", name, err)
			}
		}
	}
	m.SetPromptNotifier(func(name, rawURL, html, reason string) { // core: "prompt"; still OFNOTE captcha on stdout
		ck.pending(name, rawURL, html, reason, keys[name], func(jar map[string]string) error { return m.ApplyCookiesFor(name, jar) })
	})
	return m, sess, types, nil
}

// startSession runs a share link: Session → in-process TCP/IP stack
// (tunnel.TCPTunnel) → the core's SOCKS5 server with our credentials.
func startSession(p *carrierPlan, listen, user, pass string, ck *checks) (runningCarrier, error) {
	trans, sess, types, err := buildSession(p, ck)
	if err != nil {
		return nil, err
	}
	if err := trans.Start(); err != nil {
		return nil, err
	}
	tun := tunnel.NewTCPTunnel(trans, false)
	if err := tun.Err(); err != nil {
		_ = trans.Stop()
		return nil, err
	}
	srv := socks5.NewSOCKS5Server(listen, tun)
	if user != "" {
		srv.SetAuth(user, pass)
	}
	if err := srv.Bind(); err != nil {
		tun.Close()
		_ = trans.Stop()
		return nil, fmt.Errorf("bind %s: %w", listen, err)
	}
	go func() { _ = srv.Start() }()
	return &sessionRun{trans: trans, sess: sess, types: types, tun: tun, server: srv}, nil
}

func startCarrier(p *carrierPlan, listen, user, pass string, ck *checks) (runningCarrier, error) {
	if p.stream {
		return startStream(p, listen, user, pass, ck)
	}
	return startSession(p, listen, user, pass, ck)
}

// ── status ──────────────────────────────────────────────────────────────────

// statusTracker turns connected/not samples into OFSTATUS lines (printed on
// change only, like the v2 path).
type statusTracker struct {
	last    string
	since   time.Time // when the current not-connected spell began
	wait    time.Duration
	carrier string
}

func newStatusTracker(now time.Time, wait time.Duration) *statusTracker {
	return &statusTracker{since: now, wait: wait}
}

// step returns the lines to print for one sample.
func (s *statusTracker) step(connected bool, active string, now time.Time) []string {
	var out []string
	state := "connected"
	if !connected {
		if s.last == "connected" || s.last == "" {
			s.since = now
		}
		state = "connecting"
		if s.last == "error" || now.Sub(s.since) >= s.wait {
			state = "error"
		}
	}
	if state != s.last {
		out = append(out, "OFSTATUS "+state)
		s.last = state
	}
	if connected && active != "" && active != s.carrier {
		s.carrier = active
		out = append(out, "OFNOTE carrier "+active)
	}
	return out
}

// runCarrierMode is main() for -transport mailru|cups|session. It never
// returns on success.
func runCarrierMode(listen, user, pass string) {
	p, err := planCarrier(*flagTransport, *flagLink, *flagSecret)
	if err != nil {
		fmt.Printf("OFSTATUS error\n")
		log.Fatalf("ofbridge: %v", err)
	}
	if *flagDebug {
		utils.SetLevel(utils.LevelDebug)
	}
	if user == "" {
		log.Printf("WARNING: SOCKS5 without auth")
	}
	ck := newChecks(*flagCookies, os.Stdout)
	go ck.readCommands(os.Stdin)
	fmt.Printf("OFNOTE transport %s\n", p.label)
	fmt.Printf("OFSTATUS connecting\n")
	rc, err := startCarrier(p, listen, user, pass, ck)
	if err != nil {
		fmt.Printf("OFSTATUS error\n")
		log.Fatalf("ofbridge: %s: %v", p.label, err)
	}
	log.Printf("ofbridge: SOCKS5 on %s → %s", listen, p.label)
	st := newStatusTracker(time.Now(), carrierConnectWait)
	st.last = "connecting"
	for {
		for _, l := range st.step(rc.Connected(), rc.Active(), time.Now()) {
			fmt.Println(l)
		}
		time.Sleep(carrierPoll)
	}
}
