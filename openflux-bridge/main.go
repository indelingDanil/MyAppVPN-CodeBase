// SPDX-License-Identifier: GPL-3.0-or-later
//
// ofbridge — a tiny local SOCKS5 server whose outbound is an OpenFlux phpbox
// exit reached over plain HTTPS (transport/phpbox direct-HTTP client).
//
//	app traffic → tun2socks → Xray → SOCKS5 (this) → https://host/phpbox.php → internet
//
// The upstream OpenFlux CLI only offers the stream mode over document carriers
// (Yandex / Mail.ru / cups); the direct-HTTP client exists in the library but
// not on the command line. This bridge exposes exactly that, plus:
//   - SOCKS5 username/password auth (other local apps must not use the proxy),
//   - automatic re-session: phpbox caps each down-poll (~140 s) and the mux then
//     reports ErrSessionClosed — a fresh session is started transparently.
//
// Only CONNECT is supported (phpbox carries TCP to :80/:443). UDP ASSOCIATE is
// refused, which makes Xray/tun2socks fall back to TCP; DNS is DoH over :443.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/phpbox"
)

var (
	flagURL       = flag.String("url", "", "exit URL: https://example.com/phpbox.php (v1), or a v2 base URL not ending in .php, e.g. https://example.com/n/")
	flagToken     = flag.String("token", "", "phpbox token (PHPBOX_TOKEN on the host)")
	flagListen    = flag.String("listen", "127.0.0.1:10818", "local SOCKS5 listen address (loopback only)")
	flagUser      = flag.String("user", "", "SOCKS5 username (empty = no auth; strongly discouraged)")
	flagPass      = flag.String("pass", "", "SOCKS5 password")
	flagDebug     = flag.Bool("debug", false, "log every stream (destinations are logged!)")
	flagClient    = flag.String("client", "", "install id sent to the exit (demo exits grant a one-time trial per id)")
	flagIPCache   = flag.String("ipcache", "", "JSON file caching the exit host's last resolved IPv4; dialed when DNS fails")
	flagIP        = flag.String("ip", "", "IPv4 to dial for the exit host (skips DNS; the Host header stays the URL's host)")
	flagShortPoll = flag.String("shortpoll", "auto", "v2 down polls: auto (long-poll, switch to short if the preamble stalls), on, off")
)

// ── phpbox session management ───────────────────────────────────────────────

type upstream struct {
	url, token string
	mu         sync.Mutex
	cur        *phpbox.Mux
	// set by noteStatus
	pausedUntil time.Time
	lastState   string
	gen         uint64
	sessions    atomic.Uint64
}

func newSessionID() string {
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// client returns the current mux and its generation, starting one if needed.
// (Not phpbox.NewClient: its direct-HTTP carrier feeds phpbox.php's leading
// whitespace pad into the frame parser, which then waits for a ~0.5 GB
// "frame" forever. Our carrier strips the pad — see httpCarrier.)
func (u *upstream) client() (*phpbox.Mux, uint64) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.cur == nil {
		u.gen++
		gen := u.gen
		var car phpbox.Carrier
		kind := "phpbox"
		if isV2URL(u.url) {
			c := newV2Carrier(u.url, u.token, newSessionID())
			c.onEnd = func() { u.retire(gen) } // session gone (410 / refused / unreachable)
			c.onStatus = u.noteStatus
			car, kind = c, "of2"
		} else {
			c := newHTTPCarrier(u.url, u.token, newSessionID())
			c.onEnd = func() { u.retire(gen) } // down-poll ended → session is dead
			c.onStatus = u.noteStatus
			car = c
		}
		m := phpbox.NewMux(car)
		_ = m.Start()
		u.cur = m
		n := u.sessions.Add(1)
		log.Printf("%s: session #%d started", kind, n)
	}
	return u.cur, u.gen
}

// ── direct-HTTP carrier (two half-duplex channels, like phpbox.php expects) ──

const (
	// upBatchMax caps one up-POST. Every POST costs one full round trip to the
	// exit (~140 ms, independent of size up to 1 MiB), and they go strictly one
	// after another (the exit appends bodies to the bus in arrival order), so
	// upload ≈ batch / RTT: the old 64 KiB cap held it at ~300 KB/s.
	upBatchMax = 1 << 20
	// upQueueMax bounds the DATA waiting behind the POST in flight; Send blocks
	// beyond it, which pushes back to the app's TCP. The old 256-slot channel
	// held up to ~8 MiB (half a minute at ~300 KB/s) with every other stream's
	// handshake — and the mux's 4 s OPEN retry — stuck behind it.
	upQueueMax = 1 << 20
	// upLinger lets a burst written back to back (TLS records) join one POST.
	upLinger = 2 * time.Millisecond
)

// upQueue holds encoded frames until the up loop ships them. OPEN frames jump
// the bulk queue (a stream has no DATA before its OPEN_OK, so this cannot
// reorder a stream); everything else stays in order — a CLOSE must never pass
// its stream's DATA.
type upQueue struct {
	mu     sync.Mutex
	cond   *sync.Cond
	pri    [][]byte
	q      [][]byte
	n      int // bytes in q
	closed bool
}

func newUpQueue() *upQueue {
	q := &upQueue{}
	q.cond = sync.NewCond(&q.mu)
	return q
}

// put queues one frame, blocking while the bulk queue is full. false = closed.
func (q *upQueue) put(p []byte) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	if len(p) > 0 && p[0] == phpbox.FrameOpen {
		q.pri = append(q.pri, p)
	} else {
		for q.n >= upQueueMax && !q.closed {
			q.cond.Wait()
		}
		if q.closed {
			return false
		}
		q.q = append(q.q, p)
		q.n += len(p)
	}
	if q.closed {
		return false
	}
	q.cond.Broadcast()
	return true
}

// take appends whole queued frames to dst (priority frames first) until dst
// would exceed max. With block it waits for at least one frame. false = closed.
func (q *upQueue) take(dst []byte, max int, block bool) ([]byte, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for block && len(q.pri) == 0 && len(q.q) == 0 && !q.closed {
		q.cond.Wait()
	}
	if q.closed {
		return dst, false
	}
	for _, p := range q.pri {
		dst = append(dst, p...)
	}
	q.pri = nil
	i := 0
	for ; i < len(q.q) && (len(dst) == 0 || len(dst)+len(q.q[i]) <= max); i++ {
		dst = append(dst, q.q[i]...)
		q.n -= len(q.q[i])
		q.q[i] = nil
	}
	q.q = q.q[i:]
	if len(q.q) == 0 {
		q.q = nil
	}
	q.cond.Broadcast() // room for blocked senders
	return dst, true
}

func (q *upQueue) close() {
	q.mu.Lock()
	q.closed = true
	q.cond.Broadcast()
	q.mu.Unlock()
}

type httpCarrier struct {
	endpoint, token, session string
	hc                       *http.Client
	ctx                      context.Context
	cancel                   context.CancelFunc
	up                       *upQueue
	recv                     func([]byte)
	onEnd                    func()
	onStatus                 func(code int, reason string)
}

func newHTTPCarrier(endpoint, token, session string) *httpCarrier {
	ctx, cancel := context.WithCancel(context.Background())
	return &httpCarrier{
		endpoint: endpoint, token: token, session: session,
		hc:  newExitClient(),
		ctx: ctx, cancel: cancel,
		up: newUpQueue(),
	}
}

func (h *httpCarrier) Receive(cb func([]byte)) { h.recv = cb }
func (h *httpCarrier) Start() error            { go h.downLoop(); go h.upLoop(); return nil }
func (h *httpCarrier) Stop() error             { h.cancel(); h.up.close(); return nil }

// Send queues one encoded frame (the mux sends exactly one per call). It blocks
// while the up queue is full, so a bulk upload is paced by the exit.
func (h *httpCarrier) Send(p []byte) error {
	h.up.put(append([]byte(nil), p...))
	return nil
}

func (h *httpCarrier) url(role string) string {
	u := h.endpoint + "?k=" + h.token + "&s=" + h.session + "&r=" + role
	if *flagClient != "" {
		u += "&c=" + *flagClient // install id: the demo exit's one-time-trial key
	}
	return u
}

// upLoop ships queued frames as POST ?r=up, one at a time (the exit appends
// each body to the session bus in arrival order — concurrent POSTs could
// reorder). Whatever queued up during the previous POST goes in the next one.
func (h *httpCarrier) upLoop() {
	defer h.up.close()
	buf := make([]byte, 0, 64<<10)
	for {
		var ok bool
		if buf, ok = h.up.take(buf[:0], upBatchMax, true); !ok {
			return
		}
		if len(buf) < upBatchMax {
			time.Sleep(upLinger)
			buf, _ = h.up.take(buf, upBatchMax, false)
		}
		t0 := time.Now()
		if err := h.post(buf); err != nil {
			if h.ctx.Err() == nil {
				// The batch may or may not be on the bus: every stream in it is now
				// corrupt or stalled. End the session so the apps reconnect at once.
				log.Printf("phpbox: up POST failed (%v); %d bytes lost — restarting the session", err, len(buf))
				h.cancel()
			}
			return
		}
		if *flagDebug {
			log.Printf("up: %d B in %v", len(buf), time.Since(t0).Round(time.Millisecond))
		}
	}
}

// post sends one batch and checks the exit stored all of it ("ok <n>").
func (h *httpCarrier) post(body []byte) error {
	req, err := http.NewRequestWithContext(h.ctx, http.MethodPost, h.url("up"), bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := h.hc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err // *url.Error quotes the URL, and the URL carries the token
		}
		return err
	}
	reply, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if got := string(bytes.TrimSpace(reply)); got != "ok "+strconv.Itoa(len(body)) {
		return fmt.Errorf("exit answered %q for %d bytes", got[:min(len(got), 40)], len(body))
	}
	return nil
}

func (h *httpCarrier) downLoop() {
	defer func() {
		if h.onEnd != nil {
			h.onEnd()
		}
	}()
	req, err := http.NewRequestWithContext(h.ctx, http.MethodGet, h.url("down"), nil)
	if err != nil {
		return
	}
	resp, err := h.hc.Do(req)
	if err != nil {
		if h.ctx.Err() == nil {
			log.Printf("phpbox: down poll failed: %v", err)
		}
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		reason := resp.Header.Get("X-OF-Reason")
		log.Printf("phpbox: down poll HTTP %d %s", resp.StatusCode, reason)
		if h.onStatus != nil {
			h.onStatus(resp.StatusCode, reason)
		}
		return
	}
	if left := resp.Header.Get("X-OF-Left"); left != "" {
		fmt.Printf("OFLEFT %s\n", left) // demo trial seconds left (app shows a countdown)
	}
	if mb := resp.Header.Get("X-OF-Left-MB"); mb != "" {
		fmt.Printf("OFLEFTMB %s\n", mb) // demo trial traffic left, MB
	}
	if h.onStatus != nil {
		h.onStatus(http.StatusOK, "")
	}
	tmp := make([]byte, 32768)
	padDone := false
	for {
		n, rerr := resp.Body.Read(tmp)
		chunk := tmp[:n]
		if !padDone && n > 0 {
			// phpbox.php opens with a whitespace pad terminated by "\n".
			if i := bytes.IndexByte(chunk, '\n'); i >= 0 {
				chunk = chunk[i+1:]
				padDone = true
			} else {
				chunk = nil
			}
		}
		if len(chunk) > 0 && h.recv != nil {
			h.recv(chunk)
		}
		if rerr != nil {
			return
		}
	}
}

// ── OpenFlux v2 carrier (resumable, offset-sequenced; see PROTOCOL-v2.md) ──
//
// The session lives in a persistent server process, not in one HTTP request:
// both directions are byte-offset sequenced, so a cut request loses nothing.
//   down: GET ?r=down&from=<recv> → 4096-byte "OF2 <recv>" preamble, then the
//         down stream from exactly that offset (pad frames included; the mux
//         drops them — stream 0xFFFFFFFF never exists). Re-polled on any end.
//   up:   POST ?r=up&off=<off> with the unacknowledged bytes; the exit answers
//         "ok <upRecv>" and skips a prefix it already has, so a retry after a
//         lost answer cannot duplicate anything.

const v2PreambleLen = 4096

// Timings (vars so tests can shrink them).
var (
	v2RetryMin   = 200 * time.Millisecond // re-poll delay; first backoff step
	v2RetryMax   = 2 * time.Second        // backoff cap
	v2GiveUp     = 45 * time.Second       // continuous failure → session end
	v2ReqTimeout = 20 * time.Second       // one up POST; down: header wait and max silence (exit pads every 5 s)
	// v2DownRotate: the exit learns what we received only from a new down's
	// "from" and stops reading destinations once 8 MiB are unacknowledged, so a
	// long poll would stall a bulk download. Re-poll after this many bytes.
	v2DownRotate int64 = 4 << 20
	// v2PreambleWait: in -shortpoll auto, a long-poll whose 4096-byte preamble
	// has not fully arrived this long after the request was sent is taken as a
	// buffering proxy (e.g. a carrier's transparent HTTP proxy on :80).
	v2PreambleWait = 8 * time.Second
	// v2ShortRepoll: re-poll delay after a clean end in short-poll mode.
	v2ShortRepoll = 20 * time.Millisecond
)

// shortActive: v2 down polls ask for poll=short (the exit ends each response
// ~250 ms after a burst or at the first 5 s keepalive). Process-wide: once a
// buffering proxy is detected every later session uses short polls too.
var shortActive atomic.Bool

// switchToShortPoll turns short polls on and reports it once ("OFNOTE shortpoll").
func switchToShortPoll(why string) {
	if shortActive.CompareAndSwap(false, true) {
		log.Printf("of2: %s — switching to short polls", why)
		fmt.Printf("OFNOTE shortpoll\n")
	}
}

// isV2URL: .php URLs are the v1 phpbox exit, everything else speaks v2.
func isV2URL(raw string) bool {
	p := raw
	if u, err := url.Parse(raw); err == nil {
		p = u.Path
	}
	return !strings.HasSuffix(strings.ToLower(p), ".php")
}

// redact strips *url.Error, whose message quotes the URL (and so the token).
func redact(err error) error {
	var ue *url.Error
	if errors.As(err, &ue) {
		return ue.Err
	}
	return err
}

type v2Carrier struct {
	endpoint, token, session string
	hc                       *http.Client
	ctx                      context.Context
	cancel                   context.CancelFunc
	up                       *upQueue
	recv                     func([]byte)
	onEnd                    func()
	onStatus                 func(code int, reason string)
}

func newV2Carrier(endpoint, token, session string) *v2Carrier {
	ctx, cancel := context.WithCancel(context.Background())
	return &v2Carrier{
		endpoint: endpoint, token: token, session: session,
		hc:  newExitClient(),
		ctx: ctx, cancel: cancel,
		up: newUpQueue(),
	}
}

func (h *v2Carrier) Receive(cb func([]byte)) { h.recv = cb }
func (h *v2Carrier) Start() error            { go h.downLoop(); go h.upLoop(); return nil }
func (h *v2Carrier) Stop() error             { h.end(); return nil }

// Send queues one encoded frame; blocks while the up queue is full.
func (h *v2Carrier) Send(p []byte) error {
	h.up.put(append([]byte(nil), p...))
	return nil
}

// end stops both loops; the down loop then reports onEnd (exactly once).
func (h *v2Carrier) end() { h.cancel(); h.up.close() }

func (h *v2Carrier) url(role, extra string) string {
	sep := "?"
	if strings.Contains(h.endpoint, "?") {
		sep = "&"
	}
	u := h.endpoint + sep + "k=" + url.QueryEscape(h.token) + "&s=" + h.session
	if *flagClient != "" {
		u += "&c=" + url.QueryEscape(*flagClient)
	}
	return u + "&v=2&r=" + role + extra
}

// sleep waits d unless the carrier ends first (false).
func (h *v2Carrier) sleep(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-h.ctx.Done():
		return false
	case <-t.C:
		return true
	}
}

type downResult int

const (
	downRetry   downResult = iota // never attached (network error, 5xx): back off
	downAgain                     // attached, then ended or was cut: re-poll shortly
	downRotate                    // attached, rotated to acknowledge: re-poll now
	downGone                      // 410 / offset mismatch: the session is lost
	downRefused                   // 503/404/403: reported via onStatus
	downSwitch                    // long-poll stalled, now in short-poll mode: re-poll now
)

func (h *v2Carrier) downLoop() {
	defer func() {
		h.end()
		if h.onEnd != nil {
			h.onEnd()
		}
	}()
	var recv int64
	announced := false
	delay := v2RetryMin
	var failing time.Time
	for h.ctx.Err() == nil {
		res, err := h.downOnce(&recv, &announced)
		if h.ctx.Err() != nil {
			return
		}
		wait := v2RetryMin
		switch res {
		case downGone, downRefused:
			return
		case downRotate, downSwitch:
			failing, delay = time.Time{}, v2RetryMin
			continue
		case downAgain:
			failing, delay = time.Time{}, v2RetryMin
			if err != nil && *flagDebug {
				log.Printf("of2: down cut at %d (%v); resuming", recv, err)
			}
			if err == nil && shortActive.Load() {
				wait = v2ShortRepoll // short polls end by design: come straight back
			}
		case downRetry:
			if failing.IsZero() {
				failing = time.Now()
				log.Printf("of2: down poll failed (%v); retrying", err)
			} else if time.Since(failing) >= v2GiveUp {
				log.Printf("of2: exit unreachable for %v (%v) — ending the session", v2GiveUp, err)
				return
			}
			wait = delay
			delay = min(delay*2, v2RetryMax)
		}
		if !h.sleep(wait) {
			return
		}
	}
}

// parsePreamble returns n from a 4096-byte "OF2 <n>   …\n" preamble.
func parsePreamble(p []byte) (int64, bool) {
	if len(p) != v2PreambleLen || p[len(p)-1] != '\n' || !bytes.HasPrefix(p, []byte("OF2 ")) {
		return 0, false
	}
	f := strings.Fields(string(p[4:]))
	if len(f) != 1 {
		return 0, false
	}
	n, err := strconv.ParseInt(f[0], 10, 64)
	return n, err == nil && n >= 0
}

// downOnce runs one down poll from *recv, feeding the mux and advancing *recv.
func (h *v2Carrier) downOnce(recv *int64, announced *bool) (downResult, error) {
	ctx, cancel := context.WithCancel(h.ctx)
	defer cancel()
	wd := time.AfterFunc(v2ReqTimeout, cancel) // no headers / no bytes for that long → dead link
	defer wd.Stop()
	extra := "&from=" + strconv.FormatInt(*recv, 10)
	if shortActive.Load() {
		extra += "&poll=short"
	}
	// -shortpoll auto: the preamble must be complete within v2PreambleWait of
	// the request being written, else a proxy is buffering the response.
	var (
		pwMu    sync.Mutex
		pw      *time.Timer
		preDone bool
		stalled atomic.Bool
	)
	reqCtx := ctx
	if !shortActive.Load() && *flagShortPoll == "auto" {
		reqCtx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
			WroteRequest: func(httptrace.WroteRequestInfo) {
				pwMu.Lock()
				defer pwMu.Unlock()
				if pw == nil && !preDone {
					pw = time.AfterFunc(v2PreambleWait, func() { stalled.Store(true); cancel() })
				}
			},
		})
	}
	stopPW := func() bool { // true = the stall timer fired
		pwMu.Lock()
		defer pwMu.Unlock()
		preDone = true
		if pw != nil {
			pw.Stop()
		}
		return stalled.Load()
	}
	defer stopPW()
	isStall := func() bool {
		if stalled.Load() && h.ctx.Err() == nil {
			switchToShortPoll(fmt.Sprintf("down preamble not received within %v", v2PreambleWait))
			return true
		}
		return false
	}
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, h.url("down", extra), nil)
	if err != nil {
		return downGone, err
	}
	resp, err := h.hc.Do(req)
	if err != nil {
		if isStall() {
			return downSwitch, nil
		}
		return downRetry, redact(err)
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case http.StatusOK:
	case http.StatusGone:
		log.Printf("of2: session gone (HTTP 410)")
		return downGone, nil
	case http.StatusServiceUnavailable, http.StatusNotFound, http.StatusForbidden:
		reason := resp.Header.Get("X-OF-Reason")
		log.Printf("of2: down poll HTTP %d %s", resp.StatusCode, reason)
		if h.onStatus != nil {
			h.onStatus(resp.StatusCode, reason)
		}
		return downRefused, nil
	default:
		return downRetry, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	pre := make([]byte, v2PreambleLen)
	if _, err := io.ReadFull(resp.Body, pre); err != nil {
		if isStall() {
			return downSwitch, nil
		}
		return downRetry, fmt.Errorf("preamble: %w", redact(err))
	}
	if stopPW() && isStall() { // fired just as the preamble completed; the body is cancelled
		return downSwitch, nil
	}
	n, ok := parsePreamble(pre)
	if !ok || n != *recv {
		log.Printf("of2: bad down preamble (ok=%v, offset %d, want %d) — session lost", ok, n, *recv)
		return downGone, nil
	}
	if left := resp.Header.Get("X-OF-Left"); left != "" {
		fmt.Printf("OFLEFT %s\n", left) // demo trial seconds left (app shows a countdown)
	}
	if mb := resp.Header.Get("X-OF-Left-MB"); mb != "" {
		fmt.Printf("OFLEFTMB %s\n", mb) // demo trial traffic left, MB
	}
	if !*announced {
		*announced = true
		if h.onStatus != nil {
			h.onStatus(http.StatusOK, "")
		}
	}
	buf := make([]byte, 32<<10)
	var got int64
	for {
		wd.Reset(v2ReqTimeout)
		k, rerr := resp.Body.Read(buf)
		if k > 0 {
			if h.recv != nil {
				h.recv(buf[:k])
			}
			*recv += int64(k)
			got += int64(k)
			if got >= v2DownRotate {
				return downRotate, nil
			}
		}
		if rerr == io.EOF {
			return downAgain, nil
		}
		if rerr != nil {
			return downAgain, redact(rerr)
		}
	}
}

// postResult is one up POST's outcome; err != nil = no usable answer.
type postResult struct {
	code          int
	reply, reason string
	err           error
}

func (h *v2Carrier) post(off int64, body []byte) postResult {
	ctx, cancel := context.WithTimeout(h.ctx, v2ReqTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.url("up", "&off="+strconv.FormatInt(off, 10)), bytes.NewReader(body))
	if err != nil {
		return postResult{err: err}
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := h.hc.Do(req)
	if err != nil {
		return postResult{err: redact(err)}
	}
	reply, err := io.ReadAll(io.LimitReader(resp.Body, 512))
	resp.Body.Close()
	if err != nil {
		return postResult{err: redact(err)}
	}
	return postResult{code: resp.StatusCode, reply: strings.TrimSpace(string(reply)), reason: resp.Header.Get("X-OF-Reason")}
}

// replyNum parses "<prefix><n>" (e.g. "ok 123", "need 42").
func replyNum(reply, prefix string) (int64, bool) {
	if !strings.HasPrefix(reply, prefix) {
		return 0, false
	}
	n, err := strconv.ParseInt(strings.TrimSpace(reply[len(prefix):]), 10, 64)
	return n, err == nil
}

// dropPrefix returns b without its first n bytes, in a fresh array (the HTTP
// transport may still hold the old one as a request body).
func dropPrefix(b []byte, n int64) []byte {
	if n >= int64(len(b)) {
		return nil
	}
	return append([]byte(nil), b[n:]...)
}

// upLoop ships queued frames as sequential POST ?r=up&off=. buf holds every
// byte from off (= the exit's acknowledged upRecv) on; a batch is always all
// of buf (topped up from the queue to ~1 MiB), so a retry resends exactly the
// unacknowledged bytes and nothing is ever dropped before the exit has it.
func (h *v2Carrier) upLoop() {
	defer h.up.close()
	var buf []byte
	var off int64
	delay := v2RetryMin
	var failing time.Time
	conflicts := 0
	fail := func(what string) bool { // false = give up
		if failing.IsZero() {
			failing = time.Now()
			log.Printf("of2: up POST failed (%s); retrying %d bytes", what, len(buf))
		} else if time.Since(failing) >= v2GiveUp {
			log.Printf("of2: up failing for %v (%s) — ending the session", v2GiveUp, what)
			h.end()
			return false
		}
		if !h.sleep(delay) {
			return false
		}
		delay = min(delay*2, v2RetryMax)
		return true
	}
	for {
		var ok bool
		if len(buf) == 0 {
			if buf, ok = h.up.take(nil, upBatchMax, true); !ok {
				return
			}
			if len(buf) < upBatchMax {
				time.Sleep(upLinger)
				buf, _ = h.up.take(buf, upBatchMax, false)
			}
		} else if len(buf) < upBatchMax {
			if buf, ok = h.up.take(buf, upBatchMax, false); !ok {
				return
			}
		}
		t0 := time.Now()
		r := h.post(off, buf)
		if h.ctx.Err() != nil {
			return
		}
		switch {
		case r.err != nil:
			if !fail(r.err.Error()) {
				return
			}
		case r.code == http.StatusOK:
			n, valid := replyNum(r.reply, "ok ")
			if !valid || n < off || n > off+int64(len(buf)) {
				log.Printf("of2: exit answered %q to %d bytes at %d — ending the session", r.reply[:min(len(r.reply), 40)], len(buf), off)
				h.end()
				return
			}
			if n == off { // accepted nothing: treat like a failure, don't spin
				if !fail("nothing accepted") {
					return
				}
				continue
			}
			if *flagDebug {
				log.Printf("up: %d B in %v", n-off, time.Since(t0).Round(time.Millisecond))
			}
			buf, off = dropPrefix(buf, n-off), n
			failing, delay, conflicts = time.Time{}, v2RetryMin, 0
		case r.code == http.StatusConflict:
			x, valid := replyNum(r.reply, "need ")
			conflicts++
			if !valid || x < off || x > off+int64(len(buf)) || conflicts > 5 {
				log.Printf("of2: exit wants up offset %q, we hold [%d,%d) — ending the session", r.reply[:min(len(r.reply), 40)], off, off+int64(len(buf)))
				h.end()
				return
			}
			buf, off = dropPrefix(buf, x-off), x
		case r.code == http.StatusGone:
			log.Printf("of2: session gone (up HTTP 410)")
			h.end()
			return
		case r.code == http.StatusNotFound || r.code == http.StatusForbidden ||
			(r.code == http.StatusServiceUnavailable && r.reason != ""):
			log.Printf("of2: up POST HTTP %d %s", r.code, r.reason)
			if h.onStatus != nil {
				h.onStatus(r.code, r.reason)
			}
			h.end()
			return
		case r.code >= 500:
			if !fail(fmt.Sprintf("HTTP %d", r.code)) {
				return
			}
		default: // 400, 413, …: retrying the same bytes cannot help
			log.Printf("of2: up POST HTTP %d — ending the session", r.code)
			h.end()
			return
		}
	}
}

// retire drops the mux of generation gen (if still current) so the next call
// starts a fresh session. Streams already open on it end on their own.
func (u *upstream) retire(gen uint64) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.cur != nil && u.gen == gen {
		_ = u.cur.Close()
		u.cur = nil
	}
}

// noteStatus records how the exit answered a down-poll. A refusal (503 busy /
// quota, 404 bad token) pauses new sessions so we don't hammer the host, and is
// printed as a machine-readable "OFSTATUS <state>" line the app parses.
func (u *upstream) noteStatus(code int, reason string) {
	state := "ok"
	var pause time.Duration
	switch {
	case code == http.StatusOK:
	case code == http.StatusServiceUnavailable && reason == "quota":
		state, pause = "quota", 5*time.Minute
	case code == http.StatusServiceUnavailable && (reason == "expired" || reason == "noclient" || reason == "ended"):
		state, pause = reason, time.Hour // trial used up — don't keep knocking
	case code == http.StatusServiceUnavailable && reason == "busy":
		state, pause = "busy", 30*time.Second
	case code == http.StatusServiceUnavailable:
		state, pause = "busy", 30*time.Second
	case code == http.StatusNotFound || code == http.StatusForbidden:
		state, pause = "badtoken", 60*time.Second
	default:
		state, pause = "error", 10*time.Second
	}
	u.mu.Lock()
	if pause > 0 {
		u.pausedUntil = time.Now().Add(pause)
	}
	changed := state != u.lastState
	u.lastState = state
	u.mu.Unlock()
	if changed {
		fmt.Printf("OFSTATUS %s\n", state)
	}
}

var errPaused = errors.New("exit refused (busy/quota/token) — paused")

func (u *upstream) dial(ctx context.Context, host string, port int) (net.Conn, error) {
	u.mu.Lock()
	paused := time.Now().Before(u.pausedUntil)
	u.mu.Unlock()
	if paused {
		return nil, errPaused
	}
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		c, gen := u.client()
		conn, err := c.Dial(ctx, host, port)
		if err == nil {
			return conn, nil
		}
		lastErr = err
		if errors.Is(err, phpbox.ErrSessionClosed) {
			u.retire(gen) // the host capped the down-poll — new session, try again
			continue
		}
		// Anything else (the exit refused the target, OPEN timed out) is about
		// this connection, not the session — don't tear the session down.
		break
	}
	return nil, lastErr
}

// ── SOCKS5 (RFC 1928 / 1929), CONNECT only ──────────────────────────────────

const (
	socksVer     = 0x05
	methodNone   = 0x00
	methodUser   = 0x02
	methodNoAcc  = 0xFF
	cmdConnect   = 0x01
	atypIPv4     = 0x01
	atypDomain   = 0x03
	atypIPv6     = 0x04
	repOK        = 0x00
	repFail      = 0x01
	repCmdUnsup  = 0x07
	repAtypUnsup = 0x08
)

func reply(c net.Conn, rep byte) {
	_, _ = c.Write([]byte{socksVer, rep, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0})
}

func handshake(c net.Conn, user, pass string) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return err
	}
	if hdr[0] != socksVer {
		return fmt.Errorf("not socks5")
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	want := byte(methodNone)
	if user != "" {
		want = methodUser
	}
	ok := false
	for _, m := range methods {
		if m == want {
			ok = true
		}
	}
	if !ok {
		_, _ = c.Write([]byte{socksVer, methodNoAcc})
		return fmt.Errorf("no acceptable auth method")
	}
	if _, err := c.Write([]byte{socksVer, want}); err != nil {
		return err
	}
	if want == methodUser {
		h := make([]byte, 2)
		if _, err := io.ReadFull(c, h); err != nil {
			return err
		}
		u := make([]byte, int(h[1]))
		if _, err := io.ReadFull(c, u); err != nil {
			return err
		}
		pl := make([]byte, 1)
		if _, err := io.ReadFull(c, pl); err != nil {
			return err
		}
		p := make([]byte, int(pl[0]))
		if _, err := io.ReadFull(c, p); err != nil {
			return err
		}
		if string(u) != user || string(p) != pass {
			_, _ = c.Write([]byte{0x01, 0x01})
			return fmt.Errorf("bad credentials")
		}
		if _, err := c.Write([]byte{0x01, 0x00}); err != nil {
			return err
		}
	}
	return nil
}

func readRequest(c net.Conn) (host string, port int, err error) {
	h := make([]byte, 4)
	if _, err = io.ReadFull(c, h); err != nil {
		return
	}
	if h[1] != cmdConnect {
		reply(c, repCmdUnsup)
		return "", 0, fmt.Errorf("unsupported command %d", h[1])
	}
	switch h[3] {
	case atypIPv4:
		b := make([]byte, 4)
		if _, err = io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case atypIPv6:
		b := make([]byte, 16)
		if _, err = io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case atypDomain:
		l := make([]byte, 1)
		if _, err = io.ReadFull(c, l); err != nil {
			return
		}
		b := make([]byte, int(l[0]))
		if _, err = io.ReadFull(c, b); err != nil {
			return
		}
		host = string(b)
	default:
		reply(c, repAtypUnsup)
		return "", 0, fmt.Errorf("unsupported atyp %d", h[3])
	}
	pb := make([]byte, 2)
	if _, err = io.ReadFull(c, pb); err != nil {
		return
	}
	port = int(binary.BigEndian.Uint16(pb))
	return
}

func serve(c net.Conn, up *upstream, user, pass string) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	if err := handshake(c, user, pass); err != nil {
		return
	}
	host, port, err := readRequest(c)
	if err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	if port != 80 && port != 443 {
		// The PHP exit only dials :80/:443 — refuse early instead of a slow timeout.
		reply(c, repFail)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	remote, err := up.dial(ctx, host, port)
	cancel()
	if err != nil {
		if *flagDebug {
			log.Printf("dial %s:%d failed: %v", host, port, err)
		}
		reply(c, repFail)
		return
	}
	defer remote.Close()
	if *flagDebug {
		log.Printf("stream → %s", net.JoinHostPort(host, strconv.Itoa(port)))
	}
	reply(c, repOK)

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(remote, c); done <- struct{}{} }()
	go func() { _, _ = io.Copy(c, remote); done <- struct{}{} }()
	<-done
}

// probe performs one raw down-poll and prints what the host answers (diagnostics).
func probe(u, token string) {
	q := u + "?k=" + token + "&s=" + newSessionID() + "&r=down"
	if isV2URL(u) {
		q += "&v=2&from=0" // v2 exit: a new session; the body starts with the 4 KiB "OF2 0" preamble
	}
	t0 := time.Now()
	resp, err := http.Get(q)
	if err != nil {
		log.Fatalf("probe: %v", err)
	}
	defer resp.Body.Close()
	log.Printf("probe: HTTP %d after %v", resp.StatusCode, time.Since(t0))
	for k, v := range resp.Header {
		log.Printf("  %s: %v", k, v)
	}
	buf := make([]byte, 8192)
	total := 0
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if total == 0 {
				log.Printf("probe: first %d bytes after %v: %q", n, time.Since(t0), string(buf[:min(n, 300)]))
			}
			total += n
		}
		if err != nil {
			log.Printf("probe: body ended after %v (%d bytes): %v", time.Since(t0), total, err)
			return
		}
	}
	log.Printf("probe: still streaming after 20s (%d bytes) — long-poll works", total)
}

func main() {
	flagProbe := flag.Bool("probe", false, "do one raw down-poll and print the host's answer, then exit")
	flagDiag := flag.Bool("diag", false, "run network diagnostics against the exit, print one \"OFDIAG <json>\" line, exit 0")
	flag.Parse()
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	exitDial.cachePath = *flagIPCache
	if *flagIP != "" {
		ip := net.ParseIP(*flagIP)
		if ip == nil || ip.To4() == nil {
			fmt.Fprintln(os.Stderr, "-ip must be an IPv4 address")
			os.Exit(2)
		}
		exitDial.override = ip.To4().String()
	}
	switch *flagShortPoll {
	case "auto", "off":
	case "on":
		shortActive.Store(true)
	default:
		fmt.Fprintln(os.Stderr, "-shortpoll must be auto, on or off")
		os.Exit(2)
	}
	if *flagDiag {
		if *flagURL == "" || *flagToken == "" {
			fmt.Fprintln(os.Stderr, "usage: ofbridge -diag -url URL -token SECRET [-client ID -ipcache FILE -ip IPV4]")
			os.Exit(2)
		}
		b, _ := json.Marshal(runDiag(*flagURL, *flagToken, *flagClient))
		fmt.Printf("OFDIAG %s\n", b)
		return
	}
	if *flagProbe {
		probe(*flagURL, *flagToken)
		return
	}
	if *flagURL == "" || *flagToken == "" {
		fmt.Fprintln(os.Stderr, "usage: ofbridge -url https://host/phpbox.php -token SECRET [-listen 127.0.0.1:10818 -user U -pass P]")
		os.Exit(2)
	}
	ln, err := net.Listen("tcp", *flagListen)
	if err != nil {
		log.Fatalf("listen %s: %v", *flagListen, err)
	}
	if *flagUser == "" {
		log.Printf("WARNING: SOCKS5 without auth")
	}
	up := &upstream{url: *flagURL, token: *flagToken}
	up.client() // warm the first session right away
	log.Printf("ofbridge: SOCKS5 on %s → phpbox exit", ln.Addr())
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go serve(c, up, *flagUser, *flagPass)
	}
}
