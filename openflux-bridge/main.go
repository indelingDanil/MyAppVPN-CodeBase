// SPDX-License-Identifier: GPL-3.0-or-later

// ofbridge — a tiny local SOCKS5 server whose outbound is an OpenFlux v2 exit
// (server-node/app.js) reached over plain HTTP(S):
//
//		app traffic → tun2socks → Xray → SOCKS5 (this) → https://host/n/ → internet
//
//	  - SOCKS5 username/password auth (other local apps must not use the proxy),
//	  - one resumable v2 session (PROTOCOL-v2.md) carrying every connection as a
//	    stream (mux.go); when the exit drops the session a fresh one is started
//	    transparently.
//
// Only CONNECT is supported (the exit carries TCP to :80/:443). UDP ASSOCIATE is
// refused, which makes Xray/tun2socks fall back to TCP; DNS is DoH over :443.
// The legacy v1 PHP exit (phpbox.php) is not supported any more: such a URL is
// reported as "OFSTATUS unsupported".
//
// -transport mailru|cups|session switches to carriers of the OpenFlux core
// (Mail.ru/cups.online stream mode, openflux:// share links) behind the same
// SOCKS5; see carriers.go. The default (-transport v2) is the path above.
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
)

var (
	flagURL       = flag.String("url", "", "exit base URL (OpenFlux v2), e.g. https://example.com/n/")
	flagToken     = flag.String("token", "", "exit token (config.json on the host)")
	flagListen    = flag.String("listen", "127.0.0.1:10818", "local SOCKS5 listen address (loopback only)")
	flagUser      = flag.String("user", "", "SOCKS5 username (empty = no auth; strongly discouraged)")
	flagPass      = flag.String("pass", "", "SOCKS5 password")
	flagDebug     = flag.Bool("debug", false, "log every stream (destinations are logged!)")
	flagClient    = flag.String("client", "", "install id sent to the exit (demo exits grant a one-time trial per id)")
	flagIPCache   = flag.String("ipcache", "", "JSON file caching the exit host's last resolved IPv4; dialed when DNS fails")
	flagIP        = flag.String("ip", "", "IPv4 to dial for the exit host (skips DNS; the Host header stays the URL's host)")
	flagShortPoll = flag.String("shortpoll", "auto", "down polls: auto (long-poll, switch to short if the preamble stalls), on, off")
	flagCaps      = flag.String("caps", "on", "protocol extensions ack+mbb: on (used when the exit offers them), off (plain v2, for A/B tests)")
)

// ── session management ──────────────────────────────────────────────────────

type upstream struct {
	url, token string
	mu         sync.Mutex
	cur        *mux
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
func (u *upstream) client() (*mux, uint64) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.cur == nil {
		u.gen++
		gen := u.gen
		c := newV2Carrier(u.url, u.token, newSessionID())
		c.onEnd = func() { u.retire(gen) } // session gone (410 / refused / unreachable)
		c.onStatus = u.noteStatus
		m := newMux(c)
		_ = m.Start()
		u.cur = m
		n := u.sessions.Add(1)
		log.Printf("of2: session #%d started", n)
	}
	return u.cur, u.gen
}

// ── up queue ────────────────────────────────────────────────────────────────

const (
	// upBatchMax caps one up-POST. Every POST costs one full round trip to the
	// exit (~140 ms, independent of size up to 1 MiB), and they go strictly one
	// after another, so upload ≈ batch / RTT.
	upBatchMax = 1 << 20
	// upQueueMax bounds the DATA waiting behind the POST in flight; Send blocks
	// beyond it, which pushes back to the app's TCP.
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
	if len(p) > 0 && p[0] == frameOpen {
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

// ── OpenFlux v2 carrier (resumable, offset-sequenced; see PROTOCOL-v2.md) ──
//
// The session lives in a persistent server process, not in one HTTP request:
// both directions are byte-offset sequenced, so a cut request loses nothing.
//   down: GET ?r=down&from=<recv> → 4096-byte "OF2 <n>" preamble, then the
//         down stream from exactly that offset (pad frames included; the mux
//         drops them — stream 0xFFFFFFFF never exists). Re-polled on any end.
//   up:   POST ?r=up&off=<off> with the unacknowledged bytes; the exit answers
//         "ok <upRecv>" and skips a prefix it already has, so a retry after a
//         lost answer cannot duplicate anything.
// Extensions (negotiated per down poll: "&caps=ack,mbb" → "X-OF-Caps"):
//   ack:  the received offset is reported with "&ack=<recv>" on up POSTs and,
//         while only downloading, by tiny "POST ?r=ack&ack=<recv>" — so the down
//         poll never has to be rotated to acknowledge, and runs for X-OF-Run-Ms.
//   mbb:  make-before-break: shortly before the exit ends a poll, the next one is
//         opened with "&mbb=1"; the old one is read to its clean end and bytes
//         below the received offset are dropped.

const v2PreambleLen = 4096

// v2Caps is what this client can do (sent as "&caps=").
const v2Caps = "ack,mbb"

// Timings (vars so tests can shrink them).
var (
	v2RetryMin   = 200 * time.Millisecond // re-poll delay; first backoff step
	v2RetryMax   = 2 * time.Second        // backoff cap
	v2GiveUp     = 45 * time.Second       // continuous failure → session end
	v2ReqTimeout = 20 * time.Second       // one up POST; down: header wait and max silence (exit pads every 5 s)
	// v2DownRotate (no "ack" capability): the exit learns what we received only
	// from a new down's "from" and stops reading destinations once 8 MiB are
	// unacknowledged, so a long poll would stall a bulk download. Re-poll after
	// this many bytes.
	v2DownRotate int64 = 4 << 20
	// v2PreambleWait: in -shortpoll auto, a long-poll whose 4096-byte preamble
	// has not fully arrived this long after the request was sent is taken as a
	// buffering proxy (e.g. a carrier's transparent HTTP proxy on :80).
	v2PreambleWait = 8 * time.Second
	// v2ShortRepoll: re-poll delay after a clean end in short-poll mode.
	v2ShortRepoll = 20 * time.Millisecond

	// "ack": report the received offset after this many new bytes, or after
	// v2AckInterval once at least v2AckMin are new, or after v2AckIdle anyway
	// (idle keepalive pads only). v2AckTick is the check period.
	v2AckEvery    int64 = 1 << 20
	v2AckMin      int64 = 64 << 10
	v2AckInterval       = time.Second
	v2AckIdle           = 30 * time.Second
	v2AckTick           = 250 * time.Millisecond
	v2AckTimeout        = 10 * time.Second
	// "mbb": open the next poll this long before the exit's announced run end.
	v2MbbLead = 5 * time.Second
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

	caps      bool         // ask the exit for the extensions (-caps on)
	dmu       sync.Mutex   // serialises delivery: with mbb two polls overlap
	recvOff   atomic.Int64 // down bytes delivered to the mux so far
	announced atomic.Bool  // first poll attached (status 200 reported)
	capAck    atomic.Bool  // negotiated on the last poll
	capMbb    atomic.Bool
	runMs     atomic.Int64 // X-OF-Run-Ms of the last poll (0 = unknown)
	ackSent   atomic.Int64 // highest down offset the exit was told
	lastAck   atomic.Int64 // UnixNano of that report
	ackKick   chan struct{}
	polls     atomic.Int32 // down polls currently running
	acks      atomic.Int64 // r=ack requests sent (stats/tests)
}

func newV2Carrier(endpoint, token, session string) *v2Carrier {
	ctx, cancel := context.WithCancel(context.Background())
	h := &v2Carrier{
		endpoint: endpoint, token: token, session: session,
		hc:  newExitClient(),
		ctx: ctx, cancel: cancel,
		up:      newUpQueue(),
		caps:    *flagCaps != "off",
		ackKick: make(chan struct{}, 1),
	}
	h.lastAck.Store(time.Now().UnixNano())
	return h
}

func (h *v2Carrier) Receive(cb func([]byte)) { h.recv = cb }
func (h *v2Carrier) Start() error {
	go h.downLoop()
	go h.upLoop()
	if h.caps {
		go h.ackLoop()
	}
	return nil
}
func (h *v2Carrier) Stop() error { h.end(); return nil }

// Send queues one encoded frame; blocks while the up queue is full.
func (h *v2Carrier) Send(p []byte) error {
	if !h.up.put(append([]byte(nil), p...)) {
		return errSessionClosed
	}
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

// noteAcked records that the exit knows we hold every down byte below n.
func (h *v2Carrier) noteAcked(n int64) {
	for {
		cur := h.ackSent.Load()
		if n <= cur {
			return
		}
		if h.ackSent.CompareAndSwap(cur, n) {
			h.lastAck.Store(time.Now().UnixNano())
			return
		}
	}
}

// deliver hands the down bytes [pos, pos+len(b)) to the mux, dropping the part
// already delivered (an overlapping mbb poll). false = a gap (cannot happen
// with a correct exit: every poll starts at or below the delivered offset).
func (h *v2Carrier) deliver(pos int64, b []byte) bool {
	h.dmu.Lock()
	defer h.dmu.Unlock()
	cur := h.recvOff.Load()
	if pos > cur {
		return false
	}
	end := pos + int64(len(b))
	if end <= cur {
		return true
	}
	b = b[cur-pos:]
	if h.recv != nil {
		h.recv(b)
	}
	h.recvOff.Store(end)
	if h.capAck.Load() && end-h.ackSent.Load() >= v2AckEvery {
		select {
		case h.ackKick <- struct{}{}:
		default:
		}
	}
	return true
}

type downResult int

const (
	downRetry   downResult = iota // never attached (network error, 5xx): back off
	downAgain                     // attached, then ended or was cut: re-poll shortly
	downRotate                    // attached, rotated to acknowledge: re-poll now
	downGone                      // 410 / offset mismatch: the session is lost
	downRefused                   // 503/404/403/not an exit: reported via onStatus
	downSwitch                    // long-poll stalled, now in short-poll mode: re-poll now
)

type downOutcome struct {
	res downResult
	err error
}

func (h *v2Carrier) downLoop() {
	defer func() {
		h.end()
		if h.onEnd != nil {
			h.onEnd()
		}
	}()
	delay := v2RetryMin
	var failing time.Time
	for h.ctx.Err() == nil {
		// A poll still running (it handed over, mbb) → this one is make-before-break.
		mbb := h.polls.Load() > 0
		handoff := make(chan struct{})
		done := make(chan downOutcome, 1)
		h.polls.Add(1)
		go func() {
			r, err := h.downOnce(mbb, handoff)
			h.polls.Add(-1)
			done <- downOutcome{r, err}
		}()
		var res downResult
		var err error
		select {
		case o := <-done:
			res, err = o.res, o.err
		case <-handoff:
			// The exit ends this poll in ~v2MbbLead: open the next one now. The
			// old one keeps reading until the exit ends it cleanly.
			failing, delay = time.Time{}, v2RetryMin
			continue
		}
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
				log.Printf("of2: down cut at %d (%v); resuming", h.recvOff.Load(), err)
			}
			if err == nil && (shortActive.Load() || h.capAck.Load()) {
				// short polls end by design; an ack-capable exit ended a healthy
				// long poll (run time): come straight back
				wait = v2ShortRepoll
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

// parseCaps reads an X-OF-Caps header ("ack,mbb").
func parseCaps(v string) (ack, mbb bool) {
	for _, c := range strings.Split(v, ",") {
		switch strings.TrimSpace(c) {
		case "ack":
			ack = true
		case "mbb":
			mbb = true
		}
	}
	return
}

// unsupported reports an exit that does not speak v2 (e.g. the old phpbox.php).
func (h *v2Carrier) unsupported(why string) (downResult, error) {
	log.Printf("of2: the exit does not speak OpenFlux v2 (%s)", why)
	if h.onStatus != nil {
		h.onStatus(0, "unsupported")
	}
	return downRefused, nil
}

// downOnce runs one down poll from the delivered offset, feeding the mux.
// handoff is closed when the next poll should be opened (mbb) while this one
// keeps reading until the exit ends it.
func (h *v2Carrier) downOnce(mbb bool, handoff chan struct{}) (downResult, error) {
	ctx, cancel := context.WithCancel(h.ctx)
	defer cancel()
	wd := time.AfterFunc(v2ReqTimeout, cancel) // no headers / no bytes for that long → dead link
	defer wd.Stop()
	from := h.recvOff.Load()
	short := shortActive.Load()
	extra := "&from=" + strconv.FormatInt(from, 10)
	if short {
		extra += "&poll=short"
	}
	if h.caps {
		extra += "&caps=" + v2Caps
		if mbb && !short {
			extra += "&mbb=1"
		}
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
	if !short && *flagShortPoll == "auto" {
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
	isV2 := resp.Header.Get("X-OF-Proto") == "2"
	if h.caps {
		ack, mb := false, false
		if v, ok := resp.Header["X-Of-Caps"]; ok && len(v) > 0 {
			ack, mb = parseCaps(v[0])
		}
		h.capAck.Store(ack)
		h.capMbb.Store(mb)
		run, _ := strconv.ParseInt(resp.Header.Get("X-OF-Run-Ms"), 10, 64)
		h.runMs.Store(max(run, 0))
	}
	pre := make([]byte, v2PreambleLen)
	if _, err := io.ReadFull(resp.Body, pre); err != nil {
		if isStall() {
			return downSwitch, nil
		}
		if !isV2 && !h.announced.Load() {
			return h.unsupported("no v2 preamble")
		}
		return downRetry, fmt.Errorf("preamble: %w", redact(err))
	}
	if stopPW() && isStall() { // fired just as the preamble completed; the body is cancelled
		return downSwitch, nil
	}
	n, ok := parsePreamble(pre)
	if !ok && !isV2 && !h.announced.Load() {
		return h.unsupported("no v2 preamble")
	}
	// The exit starts at our offset — or, with "ack", at its acknowledged
	// offset when an ack overtook this poll (never beyond what we hold).
	if cur := h.recvOff.Load(); !ok || n < from || n > cur {
		log.Printf("of2: bad down preamble (ok=%v, offset %d, want %d..%d) — session lost", ok, n, from, cur)
		return downGone, nil
	}
	h.noteAcked(n)
	if left := resp.Header.Get("X-OF-Left"); left != "" {
		fmt.Printf("OFLEFT %s\n", left) // demo trial seconds left (app shows a countdown)
	}
	if mb := resp.Header.Get("X-OF-Left-MB"); mb != "" {
		fmt.Printf("OFLEFTMB %s\n", mb) // demo trial traffic left, MB
	}
	if h.announced.CompareAndSwap(false, true) && h.onStatus != nil {
		h.onStatus(http.StatusOK, "")
	}
	// mbb: hand over v2MbbLead before the exit's announced end of this poll.
	if handoff != nil && h.capMbb.Load() && !short {
		if run := time.Duration(h.runMs.Load()) * time.Millisecond; run > 2*v2MbbLead {
			t := time.AfterFunc(run-v2MbbLead, func() { close(handoff) })
			defer t.Stop()
		}
	}
	ackMode := h.capAck.Load()
	buf := make([]byte, 32<<10)
	pos := n
	var got int64
	for {
		wd.Reset(v2ReqTimeout)
		k, rerr := resp.Body.Read(buf)
		if k > 0 {
			if !h.deliver(pos, buf[:k]) {
				log.Printf("of2: down stream gap at %d — session lost", pos)
				return downGone, nil
			}
			pos += int64(k)
			got += int64(k)
			if !ackMode && got >= v2DownRotate {
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

// ackLoop reports the received offset while the up direction is quiet ("ack").
func (h *v2Carrier) ackLoop() {
	t := time.NewTicker(v2AckTick)
	defer t.Stop()
	var retryAt time.Time
	for {
		select {
		case <-h.ctx.Done():
			return
		case <-t.C:
		case <-h.ackKick:
		}
		// short polls re-poll (= acknowledge) several times a second anyway
		if !h.capAck.Load() || shortActive.Load() || time.Now().Before(retryAt) {
			continue
		}
		cur := h.recvOff.Load()
		un := cur - h.ackSent.Load()
		if un <= 0 {
			continue
		}
		since := time.Since(time.Unix(0, h.lastAck.Load()))
		if un < v2AckEvery && (un < v2AckMin || since < v2AckInterval) && since < v2AckIdle {
			continue
		}
		if !h.sendAck(cur) {
			retryAt = time.Now().Add(v2AckInterval)
		}
	}
}

// sendAck: POST ?r=ack&ack=n (empty body). Failures are harmless: the next ack,
// up POST or down poll carries a newer offset.
func (h *v2Carrier) sendAck(n int64) bool {
	ctx, cancel := context.WithTimeout(h.ctx, v2AckTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.url("ack", "&ack="+strconv.FormatInt(n, 10)), http.NoBody)
	if err != nil {
		return false
	}
	h.acks.Add(1)
	resp, err := h.hc.Do(req)
	if err != nil {
		if *flagDebug && h.ctx.Err() == nil {
			log.Printf("of2: ack %d failed: %v", n, redact(err))
		}
		return false
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 512))
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false
	}
	h.noteAcked(n)
	return true
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
	extra := "&off=" + strconv.FormatInt(off, 10)
	ack := int64(-1)
	if h.capAck.Load() {
		ack = h.recvOff.Load()
		extra += "&ack=" + strconv.FormatInt(ack, 10)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, h.url("up", extra), bytes.NewReader(body))
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
	if ack >= 0 && (resp.StatusCode == http.StatusOK || resp.StatusCode == http.StatusConflict) {
		h.noteAcked(ack)
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
// starts a fresh session. Streams already open on it end with it.
func (u *upstream) retire(gen uint64) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.cur != nil && u.gen == gen {
		_ = u.cur.Close()
		u.cur = nil
	}
}

// noteStatus records how the exit answered a down-poll. A refusal (503 busy /
// quota, 404 bad token, not a v2 exit) pauses new sessions so we don't hammer
// the host, and is printed as a machine-readable "OFSTATUS <state>" line the
// app parses.
func (u *upstream) noteStatus(code int, reason string) {
	state := "ok"
	var pause time.Duration
	switch {
	case reason == "unsupported":
		state, pause = "unsupported", time.Hour // not an OpenFlux v2 exit (e.g. old phpbox.php)
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

var errPaused = errors.New("exit refused (busy/quota/token/unsupported) — paused")

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
		if errors.Is(err, errSessionClosed) {
			u.retire(gen) // the session ended under us — new session, try again
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

// allowedPort: the exit only dials :80/:443 (more with OF_ALLOW_PORTS on a
// test exit; mirror it here via OFBRIDGE_ALLOW_PORTS for local tests).
func allowedPort(p int) bool {
	if p == 80 || p == 443 {
		return true
	}
	for _, s := range strings.Split(os.Getenv("OFBRIDGE_ALLOW_PORTS"), ",") {
		if n, err := strconv.Atoi(strings.TrimSpace(s)); err == nil && n == p {
			return true
		}
	}
	return false
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
	if !allowedPort(port) {
		// The exit only dials :80/:443 — refuse early instead of a slow timeout.
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

// probe performs one raw v2 down-poll (a new session) and prints what the host
// answers (diagnostics).
func probe(u, token string) {
	sep := "?"
	if strings.Contains(u, "?") {
		sep = "&"
	}
	q := u + sep + "k=" + url.QueryEscape(token) + "&s=" + newSessionID() + "&v=2&r=down&from=0"
	if *flagClient != "" {
		q += "&c=" + url.QueryEscape(*flagClient)
	}
	if *flagCaps != "off" {
		q += "&caps=" + v2Caps
	}
	t0 := time.Now()
	resp, err := newExitClient().Get(q)
	if err != nil {
		log.Fatalf("probe: %v", redact(err))
	}
	defer resp.Body.Close()
	log.Printf("probe: HTTP %d after %v", resp.StatusCode, time.Since(t0))
	for k, v := range resp.Header {
		log.Printf("  %s: %v", k, v)
	}
	if resp.Header.Get("X-OF-Proto") != "2" {
		log.Printf("probe: no X-OF-Proto: 2 — not an OpenFlux v2 exit?")
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
	if *flagCaps != "on" && *flagCaps != "off" {
		fmt.Fprintln(os.Stderr, "-caps must be on or off")
		os.Exit(2)
	}
	switch normTransport(*flagTransport) {
	case "v2":
	case "":
		fmt.Fprintln(os.Stderr, "-transport must be v2, mailru, cups or session")
		os.Exit(2)
	default: // a core carrier (carriers.go)
		if *flagDiag {
			b, _ := json.Marshal(runCarrierDiag(*flagTransport, *flagLink, *flagSecret))
			fmt.Printf("OFDIAG %s\n", b)
			return
		}
		runCarrierMode(*flagListen, *flagUser, *flagPass)
		return
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
		fmt.Fprintln(os.Stderr, "usage: ofbridge -url https://host/n/ -token SECRET [-listen 127.0.0.1:10818 -user U -pass P -client ID]\n"+
			"       ofbridge -transport mailru|cups -link URL [-listen ... -user U -pass P]\n"+
			"       ofbridge -transport session -link openflux://v1/... [-secret KEY -listen ... -user U -pass P]")
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
	log.Printf("ofbridge: SOCKS5 on %s → OpenFlux v2 exit (caps %s)", ln.Addr(), *flagCaps)
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
