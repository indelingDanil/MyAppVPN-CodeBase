package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/p1neappleXpress/OpenFlux/transport/phpbox"
)

func frame(t byte, sid uint32, n int) []byte {
	return phpbox.Encode(nil, phpbox.Frame{Type: t, StreamID: sid, Payload: bytes.Repeat([]byte{byte(sid)}, n)})
}

func TestUpQueueOrderAndOpenPriority(t *testing.T) {
	q := newUpQueue()
	d1 := frame(phpbox.FrameData, 1, 100)
	c1 := frame(phpbox.FrameClose, 1, 0)
	o2 := frame(phpbox.FrameOpen, 2, 10)
	for _, f := range [][]byte{d1, c1, o2} {
		if !q.put(f) {
			t.Fatal("put on open queue failed")
		}
	}
	got, ok := q.take(nil, upBatchMax, true)
	if !ok {
		t.Fatal("take failed")
	}
	want := append(append(append([]byte(nil), o2...), d1...), c1...)
	if !bytes.Equal(got, want) {
		t.Fatalf("batch order wrong: OPEN first, then DATA before CLOSE")
	}
}

func TestUpQueueBatchCapKeepsWholeFrames(t *testing.T) {
	q := newUpQueue()
	f := frame(phpbox.FrameData, 1, 40000)
	for i := 0; i < 3; i++ {
		q.put(f)
	}
	got, _ := q.take(nil, 100000, true)
	if len(got) != 2*len(f) {
		t.Fatalf("got %d bytes, want 2 whole frames (%d)", len(got), 2*len(f))
	}
	got, _ = q.take(nil, 100000, false)
	if len(got) != len(f) {
		t.Fatalf("rest: got %d, want %d", len(got), len(f))
	}
}

func TestUpQueueBackpressureAndClose(t *testing.T) {
	q := newUpQueue()
	big := frame(phpbox.FrameData, 1, upQueueMax) // fills the queue on its own
	q.put(big)
	done := make(chan bool, 1)
	go func() { done <- q.put(frame(phpbox.FrameData, 1, 10)) }()
	select {
	case <-done:
		t.Fatal("put did not block on a full queue")
	case <-time.After(50 * time.Millisecond):
	}
	// An OPEN is never held back by bulk data.
	if !q.put(frame(phpbox.FrameOpen, 3, 5)) {
		t.Fatal("OPEN blocked")
	}
	if _, ok := q.take(nil, upBatchMax*2, true); !ok {
		t.Fatal("take failed")
	}
	select {
	case ok := <-done:
		if !ok {
			t.Fatal("put failed after room was made")
		}
	case <-time.After(time.Second):
		t.Fatal("blocked put not released")
	}
	go func() { done <- q.put(big) }()
	go func() { done <- q.put(big) }() // second one blocks
	time.Sleep(20 * time.Millisecond)
	q.close()
	res := []bool{<-done, <-done}
	if res[0] && res[1] {
		t.Fatal("put after close reported success twice")
	}
	if _, ok := q.take(nil, 1, true); ok {
		t.Fatal("take on closed queue succeeded")
	}
}

// ── v2 carrier against a minimal fake v2 exit ───────────────────────────────

type fakeSess struct {
	mu       sync.Mutex
	down     []byte // retained (unacknowledged) down bytes, starting at acked
	acked    int64
	produced int64
	attach   int // bumps on every down; an older down ends when it sees a newer one
	upRecv   int64
	up       []byte // every up byte accepted, in order
	rx       []byte // echo mode: up bytes not yet parsed into frames
}

// produce appends to the sequenced down stream (s.mu held).
func (s *fakeSess) produce(b []byte) {
	s.down = append(s.down, b...)
	s.produced += int64(len(b))
}

type fakeV2 struct {
	mu   sync.Mutex
	sess map[string]*fakeSess

	downs, posts atomic.Int32
	// downHook may answer a down itself (true = handled). n counts downs from 1.
	downHook func(w http.ResponseWriter, n int, from int64) bool
	// downCut: cut the n-th down after cut body bytes (0 = never); abort = drop the connection.
	downCut func(n int) (cut int, abort bool)
	// upHook may answer a POST itself (true = handled). n counts POSTs from 1.
	upHook func(w http.ResponseWriter, n int, s *fakeSess, off int64, body []byte) bool
	echo   bool          // answer OPEN with OPEN_OK, echo DATA; a pad frame before each answer
	life   time.Duration // a down ends cleanly after this
	token  string        // accepted token
	// shortLife: a poll=short down ends cleanly after this (the real exit: 250 ms after a burst / first keepalive).
	shortLife time.Duration
	pad       time.Duration // > 0: a 4096-byte pad frame after this much idle time (sequenced)
	stallLong bool          // a long-poll down sends headers, then never the preamble (a buffering proxy)

	qmu     sync.Mutex
	queries []string // every down's raw query
}

func (f *fakeV2) downQueries() []string {
	f.qmu.Lock()
	defer f.qmu.Unlock()
	return append([]string(nil), f.queries...)
}

func newFakeV2(t *testing.T) (*fakeV2, *httptest.Server) {
	f := &fakeV2{sess: map[string]*fakeSess{}, life: 2 * time.Second, token: "tok", shortLife: 100 * time.Millisecond}
	srv := httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(srv.Close)
	return f, srv
}

func (f *fakeV2) only() *fakeSess {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, s := range f.sess {
		return s
	}
	return nil
}

func (f *fakeV2) serve(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	w.Header().Set("X-OF-Proto", "2")
	if q.Get("k") != f.token || q.Get("v") != "2" {
		http.Error(w, "no", http.StatusNotFound)
		return
	}
	id := q.Get("s")
	switch q.Get("r") {
	case "down":
		f.serveDown(w, r, id)
	case "up":
		f.serveUp(w, r, id)
	default:
		http.Error(w, "bad", http.StatusBadRequest)
	}
}

func (f *fakeV2) serveDown(w http.ResponseWriter, r *http.Request, id string) {
	from, _ := strconv.ParseInt(r.URL.Query().Get("from"), 10, 64)
	short := r.URL.Query().Get("poll") == "short"
	f.qmu.Lock()
	f.queries = append(f.queries, r.URL.RawQuery)
	f.qmu.Unlock()
	n := int(f.downs.Add(1))
	if f.downHook != nil && f.downHook(w, n, from) {
		return
	}
	if f.stallLong && !short {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		select { // the proxy holds the body until the response ends
		case <-r.Context().Done():
		case <-time.After(3 * time.Second):
		}
		return
	}
	f.mu.Lock()
	s := f.sess[id]
	if s == nil {
		if from != 0 {
			f.mu.Unlock()
			http.Error(w, "gone", http.StatusGone)
			return
		}
		s = &fakeSess{}
		f.sess[id] = s
	}
	f.mu.Unlock()
	s.mu.Lock()
	if from < s.acked || from > s.produced {
		s.mu.Unlock()
		http.Error(w, "gone", http.StatusGone)
		return
	}
	s.down = s.down[from-s.acked:]
	s.acked = from
	s.attach++
	my := s.attach
	s.mu.Unlock()

	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(preamble(from))
	fl := w.(http.Flusher)
	fl.Flush()
	cut, abort := 0, false
	if f.downCut != nil {
		cut, abort = f.downCut(n)
	}
	pos, sent := from, 0
	life := f.life
	if short {
		life = min(life, f.shortLife)
	}
	deadline := time.Now().Add(life)
	lastWrite := time.Now()
	for time.Now().Before(deadline) {
		if f.pad > 0 && time.Since(lastWrite) >= f.pad {
			lastWrite = time.Now()
			s.mu.Lock()
			s.produce(phpbox.Encode(nil, phpbox.Frame{Type: phpbox.FrameData, StreamID: 0xFFFFFFFF, Payload: make([]byte, 4087)}))
			s.mu.Unlock()
		}
		s.mu.Lock()
		if s.attach != my || pos < s.acked {
			s.mu.Unlock()
			return // replaced by a newer down
		}
		chunk := s.down[pos-s.acked:]
		if len(chunk) > 50000 {
			chunk = chunk[:50000]
		}
		if cut > 0 && len(chunk) > cut-sent {
			chunk = chunk[:cut-sent]
		}
		chunk = append([]byte(nil), chunk...)
		s.mu.Unlock()
		if len(chunk) > 0 {
			if _, err := w.Write(chunk); err != nil {
				return
			}
			fl.Flush()
			lastWrite = time.Now()
			pos += int64(len(chunk))
			sent += len(chunk)
			if cut > 0 && sent >= cut {
				if abort {
					panic(http.ErrAbortHandler)
				}
				return
			}
			continue
		}
		select {
		case <-r.Context().Done():
			return
		case <-time.After(2 * time.Millisecond):
		}
	}
}

func preamble(n int64) []byte {
	p := bytes.Repeat([]byte(" "), v2PreambleLen)
	copy(p, fmt.Sprintf("OF2 %d", n))
	p[v2PreambleLen-1] = '\n'
	return p
}

func (f *fakeV2) serveUp(w http.ResponseWriter, r *http.Request, id string) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return
	}
	off, _ := strconv.ParseInt(r.URL.Query().Get("off"), 10, 64)
	f.mu.Lock()
	s := f.sess[id]
	f.mu.Unlock()
	if s == nil {
		http.Error(w, "gone", http.StatusGone)
		return
	}
	n := int(f.posts.Add(1))
	if f.upHook != nil && f.upHook(w, n, s, off, body) {
		return
	}
	need, ok := f.accept(s, off, body)
	if !ok {
		http.Error(w, fmt.Sprintf("need %d", need), http.StatusConflict)
		return
	}
	fmt.Fprintf(w, "ok %d", need)
}

// accept applies a body at off, skipping a prefix already held. It returns
// the new upRecv; false = the body starts beyond it (409).
func (f *fakeV2) accept(s *fakeSess, off int64, body []byte) (int64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if off > s.upRecv {
		return s.upRecv, false
	}
	skip := s.upRecv - off
	if skip >= int64(len(body)) {
		return s.upRecv, true
	}
	nb := body[skip:]
	s.up = append(s.up, nb...)
	s.upRecv += int64(len(nb))
	if f.echo {
		s.rx = append(s.rx, nb...)
		for {
			fr, rest, ok := phpbox.TakeFrame(s.rx)
			if !ok {
				break
			}
			s.rx = rest
			s.produce(phpbox.Encode(nil, phpbox.Frame{Type: phpbox.FrameData, StreamID: 0xFFFFFFFF, Payload: make([]byte, 4087)}))
			switch fr.Type {
			case phpbox.FrameOpen:
				s.produce(phpbox.Encode(nil, phpbox.Frame{Type: phpbox.FrameOpenOK, StreamID: fr.StreamID}))
			case phpbox.FrameData:
				s.produce(phpbox.Encode(nil, fr))
			}
		}
	}
	return s.upRecv, true
}

// testCarrier wires a v2 carrier to collectors.
type testCarrier struct {
	*v2Carrier
	mu       sync.Mutex
	got      []byte
	statuses []string
	ended    chan struct{}
}

func (c *testCarrier) received() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]byte(nil), c.got...)
}

func (c *testCarrier) isEnded() bool {
	select {
	case <-c.ended:
		return true
	default:
		return false
	}
}

func fastV2(t *testing.T) {
	a, b, g, r, rot := v2RetryMin, v2RetryMax, v2GiveUp, v2ReqTimeout, v2DownRotate
	v2RetryMin, v2RetryMax, v2GiveUp, v2ReqTimeout = 5*time.Millisecond, 50*time.Millisecond, 3*time.Second, 2*time.Second
	t.Cleanup(func() { v2RetryMin, v2RetryMax, v2GiveUp, v2ReqTimeout, v2DownRotate = a, b, g, r, rot })
}

// startV2 builds a carrier against srv. Unless mux, it is started with a
// collecting receiver; with mux the caller hands it to phpbox.NewMux.
func startV2(t *testing.T, srv *httptest.Server, mux bool) *testCarrier {
	c := &testCarrier{v2Carrier: newV2Carrier(srv.URL+"/n/", "tok", newSessionID()), ended: make(chan struct{})}
	var once sync.Once
	c.onEnd = func() { once.Do(func() { close(c.ended) }) }
	c.onStatus = func(code int, reason string) {
		c.mu.Lock()
		c.statuses = append(c.statuses, fmt.Sprintf("%d %s", code, reason))
		c.mu.Unlock()
	}
	if !mux {
		c.Receive(func(b []byte) {
			c.mu.Lock()
			c.got = append(c.got, b...)
			c.mu.Unlock()
		})
		_ = c.Start()
	}
	t.Cleanup(func() {
		_ = c.Stop()
		select { // let the loops wind down before the next test touches the timing vars
		case <-c.ended:
		case <-time.After(5 * time.Second):
		}
	})
	return c
}

func waitFor(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	end := time.Now().Add(d)
	for !cond() {
		if time.Now().After(end) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

func produceSlowly(f *fakeV2, data []byte, piece int) {
	for len(data) > 0 {
		k := min(piece, len(data))
		s := f.only()
		s.mu.Lock()
		s.produce(data[:k])
		s.mu.Unlock()
		data = data[k:]
		time.Sleep(time.Millisecond)
	}
}

func TestV2URLSelectionAndPreamble(t *testing.T) {
	for u, want := range map[string]bool{
		"https://h/phpbox.php":     false,
		"https://h/x/PHPBOX.PHP?a": false,
		"https://h/n/":             true,
		"http://h:8080/n":          true,
	} {
		if got := isV2URL(u); got != want {
			t.Errorf("isV2URL(%q) = %v, want %v", u, got, want)
		}
	}
	if n, ok := parsePreamble(preamble(42)); !ok || n != 42 {
		t.Fatalf("valid preamble: %d %v", n, ok)
	}
	bad := preamble(1)
	bad[v2PreambleLen-1] = ' '
	if _, ok := parsePreamble(bad); ok {
		t.Fatal("preamble without newline accepted")
	}
	if _, ok := parsePreamble(append([]byte("OF1 0"), preamble(0)[5:]...)); ok {
		t.Fatal("wrong magic accepted")
	}
}

func TestV2URLNeverLeaksIntoErrors(t *testing.T) {
	c := newV2Carrier("http://127.0.0.1:1/n/", "SECRETTOKEN", newSessionID())
	defer c.Stop()
	r := c.post(0, []byte("x"))
	if r.err == nil {
		t.Fatal("expected a dial error")
	}
	if bytes.Contains([]byte(r.err.Error()), []byte("SECRETTOKEN")) {
		t.Fatalf("token leaked: %v", r.err)
	}
}

func TestV2DownResumeAfterCuts(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.downCut = func(n int) (int, bool) { return 40000 + n*997, n%2 == 0 } // alternate clean ends and aborts
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	want := randBytes(2 << 20)
	go produceSlowly(f, want, 30000)
	waitFor(t, 20*time.Second, "all down bytes", func() bool { return len(c.received()) >= len(want) })
	time.Sleep(50 * time.Millisecond)
	if got := c.received(); !bytes.Equal(got, want) {
		t.Fatalf("down stream corrupted across cuts: got %d bytes, want %d", len(got), len(want))
	}
	if f.downs.Load() < 10 {
		t.Fatalf("expected many cut/resumed polls, got %d", f.downs.Load())
	}
	if c.isEnded() {
		t.Fatal("session ended on recoverable cuts")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.statuses) != 1 || c.statuses[0] != "200 " {
		t.Fatalf("statuses %v, want a single 200", c.statuses)
	}
}

func TestV2DownRotateAcknowledges(t *testing.T) {
	fastV2(t)
	v2DownRotate = 100000
	f, srv := newFakeV2(t)
	f.life = time.Minute
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	want := randBytes(1 << 20)
	go produceSlowly(f, want, 20000)
	waitFor(t, 10*time.Second, "all down bytes", func() bool { return len(c.received()) >= len(want) })
	if !bytes.Equal(c.received(), want) {
		t.Fatal("down stream corrupted across rotations")
	}
	s := f.only()
	s.mu.Lock()
	acked := s.acked
	s.mu.Unlock()
	if acked < 800000 {
		t.Fatalf("exit saw acks only up to %d", acked)
	}
}

func sendFrames(t *testing.T, c *testCarrier, n int) []byte {
	var all []byte
	for i := 0; i < n; i++ {
		fr := frame(phpbox.FrameData, uint32(i%7+1), 1+(i*7919)%30000)
		all = append(all, fr...)
		if err := c.Send(fr); err != nil {
			t.Fatal(err)
		}
	}
	return all
}

func upBytes(f *fakeV2) []byte {
	s := f.only()
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]byte(nil), s.up...)
}

func TestV2UpRetryNoLossNoDup(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.upHook = func(w http.ResponseWriter, n int, s *fakeSess, off int64, body []byte) bool {
		if n > 40 {
			return false
		}
		switch n % 3 {
		case 1: // the exit stores the batch, but the answer is lost
			f.accept(s, off, body)
			panic(http.ErrAbortHandler)
		case 2: // nothing stored
			http.Error(w, "bad gateway", http.StatusBadGateway)
			return true
		}
		return false
	}
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	var want []byte
	for i := 0; i < 20; i++ {
		want = append(want, sendFrames(t, c, 15)...)
		time.Sleep(3 * time.Millisecond)
	}
	waitFor(t, 20*time.Second, "all up bytes", func() bool { return len(upBytes(f)) >= len(want) })
	time.Sleep(100 * time.Millisecond)
	if got := upBytes(f); !bytes.Equal(got, want) {
		t.Fatalf("up stream corrupted by retries: got %d bytes, want %d", len(got), len(want))
	}
	if f.posts.Load() < 6 {
		t.Fatalf("expected many POSTs incl. failures, got %d", f.posts.Load())
	}
	if c.isEnded() {
		t.Fatal("session ended on retryable POST failures")
	}
}

func TestV2Up409AndPartialAck(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.upHook = func(w http.ResponseWriter, n int, s *fakeSess, off int64, body []byte) bool {
		switch n {
		case 2, 5: // a "need" naming the exit's real offset: resend from there
			s.mu.Lock()
			rv := s.upRecv
			s.mu.Unlock()
			http.Error(w, fmt.Sprintf("need %d", rv), http.StatusConflict)
			return true
		case 3: // takes only half of the batch
			rv, _ := f.accept(s, off, body[:len(body)/2])
			fmt.Fprintf(w, "ok %d", rv)
			return true
		}
		return false
	}
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	var want []byte
	for i := 0; i < 8; i++ {
		want = append(want, sendFrames(t, c, 10)...)
		waitFor(t, 5*time.Second, "batch", func() bool { return len(upBytes(f)) >= len(want) })
	}
	if got := upBytes(f); !bytes.Equal(got, want) {
		t.Fatalf("up stream wrong after 409/partial ack: got %d, want %d", len(got), len(want))
	}
	if f.posts.Load() < 8 {
		t.Fatalf("hooks not exercised: %d POSTs", f.posts.Load())
	}
	if c.isEnded() {
		t.Fatal("session ended on a resolvable 409")
	}
}

func TestV2Up409BeyondBufferEnds(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.upHook = func(w http.ResponseWriter, n int, s *fakeSess, off int64, body []byte) bool {
		if n == 2 {
			http.Error(w, "need 0", http.StatusConflict) // the exit lost bytes we already dropped
			return true
		}
		return false
	}
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	sendFrames(t, c, 3)
	waitFor(t, 5*time.Second, "first batch", func() bool { return len(upBytes(f)) > 0 })
	sendFrames(t, c, 3)
	waitFor(t, 5*time.Second, "session end", c.isEnded)
}

func TestV2GoneEndsSession(t *testing.T) {
	t.Run("down", func(t *testing.T) {
		fastV2(t)
		f, srv := newFakeV2(t)
		f.downCut = func(int) (int, bool) { return 1000, false }
		c := startV2(t, srv, false)
		waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
		s := f.only()
		s.mu.Lock()
		s.produce(randBytes(5000))
		s.mu.Unlock()
		waitFor(t, 5*time.Second, "some bytes", func() bool { return len(c.received()) >= 2000 })
		f.mu.Lock()
		f.sess = map[string]*fakeSess{} // exit restarted: the session is unknown now
		f.mu.Unlock()
		waitFor(t, 5*time.Second, "session end after 410", c.isEnded)
	})
	t.Run("up", func(t *testing.T) {
		fastV2(t)
		f, srv := newFakeV2(t)
		f.life = time.Minute
		c := startV2(t, srv, false)
		waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
		f.mu.Lock()
		f.sess = map[string]*fakeSess{} // the attached down keeps streaming; the POST gets 410
		f.mu.Unlock()
		sendFrames(t, c, 1)
		waitFor(t, 5*time.Second, "session end after up 410", c.isEnded)
	})
}

func TestV2PreambleMismatchEnds(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.downCut = func(int) (int, bool) { return 100, false }
	f.downHook = func(w http.ResponseWriter, n int, from int64) bool {
		if n < 2 {
			return false
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(preamble(from + 7))
		return true
	}
	c := startV2(t, srv, false)
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	s := f.only()
	s.mu.Lock()
	s.produce(randBytes(500))
	s.mu.Unlock()
	waitFor(t, 5*time.Second, "session end on offset mismatch", c.isEnded)
	if got := len(c.received()); got != 100 {
		t.Fatalf("received %d bytes, want exactly the 100 before the bad preamble", got)
	}
}

func TestV2RefusedReportsStatus(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.downHook = func(w http.ResponseWriter, n int, from int64) bool {
		w.Header().Set("X-OF-Reason", "busy")
		http.Error(w, "busy", http.StatusServiceUnavailable)
		return true
	}
	c := startV2(t, srv, false)
	waitFor(t, 5*time.Second, "session end on 503", c.isEnded)
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.statuses) != 1 || c.statuses[0] != "503 busy" {
		t.Fatalf("statuses %v, want [503 busy]", c.statuses)
	}
}

func TestV2TransientRecoverAndGiveUp(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.downHook = func(w http.ResponseWriter, n int, from int64) bool {
		if n <= 4 {
			http.Error(w, "bad gateway", http.StatusBadGateway)
			return true
		}
		return false
	}
	c := startV2(t, srv, false)
	waitFor(t, 5*time.Second, "recovery after 502s", func() bool { return f.only() != nil })
	if c.isEnded() {
		t.Fatal("ended on transient 502s")
	}

	v2GiveUp = 300 * time.Millisecond
	f2, srv2 := newFakeV2(t)
	f2.downHook = func(w http.ResponseWriter, n int, from int64) bool {
		http.Error(w, "bad gateway", http.StatusBadGateway)
		return true
	}
	c2 := startV2(t, srv2, false)
	waitFor(t, 5*time.Second, "give-up after continuous failure", c2.isEnded)
}

func TestV2MuxEchoThroughCutsAndPads(t *testing.T) {
	fastV2(t)
	f, srv := newFakeV2(t)
	f.echo = true
	f.downCut = func(n int) (int, bool) { return 30000 + n*331, n%2 == 1 }
	c := startV2(t, srv, true)
	m := phpbox.NewMux(c)
	_ = m.Start()
	defer m.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := m.Dial(ctx, "example.com", 443)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	want := randBytes(300000)
	go func() { _, _ = conn.Write(want) }()
	got := make([]byte, len(want))
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("echo corrupted")
	}
	if c.isEnded() {
		t.Fatal("session ended")
	}
}

// ── exit dialing: DNS fallback to the IP cache ──────────────────────────────

type fakeResolver struct {
	mu    sync.Mutex
	addrs []net.IPAddr
	err   error
	calls int
}

func (r *fakeResolver) lookup(ctx context.Context, host string) ([]net.IPAddr, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls++
	if ip := net.ParseIP(host); ip != nil {
		return []net.IPAddr{{IP: ip}}, nil
	}
	return r.addrs, r.err
}

func (r *fakeResolver) set(addrs []net.IPAddr, err error) {
	r.mu.Lock()
	r.addrs, r.err = addrs, err
	r.mu.Unlock()
}

func testDialer(t *testing.T, res *fakeResolver) *exitDialer {
	d := newExitDialer()
	d.lookup = res.lookup
	d.cachePath = filepath.Join(t.TempDir(), "ipcache.json")
	return d
}

func TestExitDialerCacheWriteAndFallback(t *testing.T) {
	res := &fakeResolver{}
	d := testDialer(t, res)
	ctx := context.Background()

	res.set([]net.IPAddr{{IP: net.ParseIP("2001:db8::1")}, {IP: net.ParseIP("203.0.113.7")}}, nil)
	ip, how, err := d.resolve(ctx, "exit.example")
	if err != nil || ip != "203.0.113.7" || how != "dns" {
		t.Fatalf("dns: %q %q %v (want the IPv4)", ip, how, err)
	}
	b, err := os.ReadFile(d.cachePath)
	if err != nil {
		t.Fatalf("cache not written: %v", err)
	}
	var e ipCacheEntry
	if json.Unmarshal(b, &e) != nil || e.Host != "exit.example" || e.IP != "203.0.113.7" || e.At == 0 {
		t.Fatalf("cache content %s", b)
	}

	// A fresh dialer (new process) on a network whose DNS fails.
	d2 := newExitDialer()
	d2.lookup, d2.cachePath = res.lookup, d.cachePath
	res.set(nil, &net.DNSError{Err: "server misbehaving", Name: "exit.example", IsTimeout: true})
	ip, how, err = d2.resolve(ctx, "exit.example")
	if err != nil || ip != "203.0.113.7" || how != "cache" {
		t.Fatalf("fallback: %q %q %v", ip, how, err)
	}
	// No addresses at all is a failure too.
	res.set(nil, nil)
	if ip, how, _ = d2.resolve(ctx, "exit.example"); how != "cache" || ip != "203.0.113.7" {
		t.Fatalf("empty answer: %q %q", ip, how)
	}
	// The cache is per host.
	res.set(nil, errors.New("boom"))
	if _, _, err = d2.resolve(ctx, "other.example"); err == nil {
		t.Fatal("other host used a foreign cache entry")
	}
	// -ip skips DNS.
	d2.override = "198.51.100.9"
	calls := res.calls
	if ip, how, _ = d2.resolve(ctx, "exit.example"); ip != "198.51.100.9" || how != "override" || res.calls != calls {
		t.Fatalf("override: %q %q (lookups %d→%d)", ip, how, calls, res.calls)
	}
}

func TestExitDialerKeepsHostHeader(t *testing.T) {
	var gotHost atomic.Value
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotHost.Store(r.Host)
		fmt.Fprint(w, "hi")
	}))
	defer srv.Close()
	_, port, _ := net.SplitHostPort(srv.Listener.Addr().String())
	res := &fakeResolver{err: &net.DNSError{Err: "no such host", Name: "exit.invalid", IsNotFound: true}}
	d := testDialer(t, res)
	b, _ := json.Marshal(ipCacheEntry{Host: "exit.invalid", IP: "127.0.0.1", At: 1})
	if err := os.WriteFile(d.cachePath, b, 0o600); err != nil {
		t.Fatal(err)
	}
	hc := &http.Client{Transport: newExitTransport(d), Timeout: 5 * time.Second}
	resp, err := hc.Get("http://exit.invalid:" + port + "/n/")
	if err != nil {
		t.Fatalf("request via cached IP failed: %v", err)
	}
	resp.Body.Close()
	if h := gotHost.Load(); h != "exit.invalid:"+port {
		t.Fatalf("Host header %v, want the original host", h)
	}
	// Without a cache the DNS error surfaces, mapped to a short string.
	d.cachePath, d.mem = "", nil
	hc = &http.Client{Transport: newExitTransport(d), Timeout: 5 * time.Second}
	_, err = hc.Get("http://exit.invalid:" + port + "/n/")
	if err == nil || errStr(err) != "no such host" {
		t.Fatalf("want 'no such host', got %v (%q)", err, errStr(err))
	}
}

func TestErrStr(t *testing.T) {
	for _, c := range []struct {
		err  error
		want string
	}{
		{&net.OpError{Op: "dial", Err: os.NewSyscallError("connect", syscall.EPERM)}, "permission denied"},
		{&net.OpError{Op: "dial", Err: os.NewSyscallError("connect", syscall.EACCES)}, "permission denied"},
		{&net.OpError{Op: "dial", Err: os.NewSyscallError("connect", syscall.ECONNREFUSED)}, "connection refused"},
		{&net.OpError{Op: "dial", Err: os.NewSyscallError("connect", syscall.ENETUNREACH)}, "network is unreachable"},
		{&net.DNSError{Err: "no such host", Name: "x", IsNotFound: true}, "no such host"},
		{context.DeadlineExceeded, "i/o timeout"},
		{&url.Error{Op: "Get", URL: "http://h/?k=SECRET", Err: errors.New("dial tcp: i/o timeout")}, "i/o timeout"},
		{&url.Error{Op: "Get", URL: "http://h/?k=SECRET", Err: errors.New("weird")}, "weird"},
	} {
		if got := errStr(c.err); got != c.want {
			t.Errorf("errStr(%v) = %q, want %q", c.err, got, c.want)
		}
	}
}

// ── short-poll auto switch ──────────────────────────────────────────────────

func resetShort(t *testing.T) {
	old := v2PreambleWait
	shortActive.Store(false)
	t.Cleanup(func() { shortActive.Store(false); v2PreambleWait = old })
}

func TestV2ShortPollAutoSwitch(t *testing.T) {
	fastV2(t)
	resetShort(t)
	v2PreambleWait = 150 * time.Millisecond
	f, srv := newFakeV2(t)
	f.stallLong = true // headers come, the preamble never does (proxy buffering)
	c := startV2(t, srv, false)
	waitFor(t, 5*time.Second, "short poll", func() bool {
		q := f.downQueries()
		return len(q) >= 2 && strings.Contains(q[len(q)-1], "poll=short")
	})
	if !shortActive.Load() {
		t.Fatal("short mode not recorded")
	}
	if strings.Contains(f.downQueries()[0], "poll=short") {
		t.Fatal("first poll was already short")
	}
	waitFor(t, 2*time.Second, "session", func() bool { return f.only() != nil })
	want := randBytes(300000)
	go produceSlowly(f, want, 1500)
	waitFor(t, 10*time.Second, "all down bytes over short polls", func() bool { return len(c.received()) >= len(want) })
	if !bytes.Equal(c.received(), want) {
		t.Fatal("down stream corrupted over short polls")
	}
	if c.isEnded() {
		t.Fatal("session ended")
	}
	if n := f.downs.Load(); n < 4 {
		t.Fatalf("expected many short polls, got %d", n)
	}
	for _, q := range f.downQueries()[1:] {
		if !strings.Contains(q, "poll=short") {
			t.Fatalf("a later poll was long: %s", q)
		}
	}
}

func TestV2LongPollStaysLong(t *testing.T) {
	fastV2(t)
	resetShort(t)
	v2PreambleWait = 150 * time.Millisecond
	f, srv := newFakeV2(t)
	f.life = 400 * time.Millisecond // clean ends, each well past the preamble wait
	_ = startV2(t, srv, false)
	waitFor(t, 5*time.Second, "several polls", func() bool { return f.downs.Load() >= 3 })
	if shortActive.Load() {
		t.Fatal("switched to short polls on a healthy long-poll")
	}
}

// ── -diag ───────────────────────────────────────────────────────────────────

func fastDiag(t *testing.T) *fakeResolver {
	a, b, c, d, e, g := diagTCPTimeout, diagHTTPTimeout, diagPreambleWait, diagStreamWait, diagShortMax, exitDial
	diagTCPTimeout, diagHTTPTimeout, diagPreambleWait, diagStreamWait, diagShortMax = time.Second, 2*time.Second, 600*time.Millisecond, 500*time.Millisecond, 2*time.Second
	res := &fakeResolver{}
	exitDial = testDialer(t, res)
	t.Cleanup(func() {
		diagTCPTimeout, diagHTTPTimeout, diagPreambleWait, diagStreamWait, diagShortMax, exitDial = a, b, c, d, e, g
	})
	return res
}

func diagJSON(t *testing.T, r *diagReport) map[string]any {
	t.Helper()
	b, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(b, []byte("SECRETTOKEN")) {
		t.Fatalf("token leaked into the report: %s", b)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"dns_system", "ipcache", "tcp", "http_probe", "stream_long", "stream_short", "up_post", "summary"} {
		if _, ok := m[k]; !ok {
			t.Fatalf("report lacks %q: %s", k, b)
		}
	}
	return m
}

func TestDiagHealthy(t *testing.T) {
	fastDiag(t)
	f, srv := newFakeV2(t)
	f.token = "SECRETTOKEN"
	f.pad = 100 * time.Millisecond
	r := runDiag(srv.URL+"/n/", "SECRETTOKEN", "client-1234")
	m := diagJSON(t, r)
	if r.Summary != "ok" {
		t.Fatalf("summary %q: %v", r.Summary, m)
	}
	if r.TCP.Family != "ipv4" || r.TCP.Source != "dns" || len(r.DNS.IPv4) != 1 {
		t.Fatalf("dns/tcp: %+v %+v", r.DNS, r.TCP)
	}
	if r.HTTPProbe.Status != 404 || !r.HTTPProbe.Proto {
		t.Fatalf("probe: %+v", r.HTTPProbe)
	}
	l, s := r.StreamLong, r.StreamShort
	if l.Status != 200 || l.HeadersMs < 0 || l.PreambleMs < 0 || l.StreamingOK == nil || !*l.StreamingOK {
		t.Fatalf("long: %+v", l)
	}
	if s.Status != 200 || s.PreambleMs < 0 || s.Bytes < v2PreambleLen || s.Err != "" {
		t.Fatalf("short: %+v", s)
	}
	if r.UpPost == nil || r.UpPost.Status != 200 || r.UpPost.Body != "ok 0" {
		t.Fatalf("up: %+v", r.UpPost)
	}
	for _, q := range f.downQueries() {
		if strings.Contains(q, "SECRETTOKEN") && !strings.Contains(q, "c=client-1234") {
			t.Fatalf("session poll without the client id: %s", q)
		}
	}
}

func TestDiagBufferingProxy(t *testing.T) {
	fastDiag(t)
	f, srv := newFakeV2(t)
	f.token = "SECRETTOKEN"
	f.stallLong = true
	r := runDiag(srv.URL+"/n/", "SECRETTOKEN", "")
	diagJSON(t, r)
	if r.Summary != "proxy_buffering_use_shortpoll" {
		t.Fatalf("summary %q, long %+v short %+v", r.Summary, r.StreamLong, r.StreamShort)
	}
	if r.StreamLong.PreambleMs != -1 || r.StreamLong.StreamingOK == nil || *r.StreamLong.StreamingOK {
		t.Fatalf("long: %+v", r.StreamLong)
	}
}

func TestDiagRefusals(t *testing.T) {
	fastDiag(t)
	f, srv := newFakeV2(t)
	f.token = "SECRETTOKEN"
	if r := runDiag(srv.URL+"/n/", "SECRETTOKEN-wrong", ""); r.Summary != "token_rejected" {
		t.Fatalf("bad token: %q", r.Summary)
	}
	f.downHook = func(w http.ResponseWriter, n int, from int64) bool {
		w.Header().Set("X-OF-Reason", "expired")
		http.Error(w, "busy", http.StatusServiceUnavailable)
		return true
	}
	if r := runDiag(srv.URL+"/n/", "SECRETTOKEN", "c-12345678"); r.Summary != "demo_refused:expired" {
		t.Fatalf("demo: %q", r.Summary)
	}
}

func TestDiagDNSAndTCPFailures(t *testing.T) {
	res := fastDiag(t)
	f, srv := newFakeV2(t)
	f.token = "SECRETTOKEN"
	f.pad = 100 * time.Millisecond
	_, port, _ := net.SplitHostPort(srv.Listener.Addr().String())
	u := "http://exit.invalid:" + port + "/n/"
	res.set(nil, &net.DNSError{Err: "no such host", Name: "exit.invalid", IsNotFound: true})
	r := runDiag(u, "SECRETTOKEN", "")
	diagJSON(t, r)
	if r.Summary != "dns_failed" || r.DNS.Err != "no such host" {
		t.Fatalf("no cache: %q %+v", r.Summary, r.DNS)
	}
	b, _ := json.Marshal(ipCacheEntry{Host: "exit.invalid", IP: "127.0.0.1", At: 1})
	_ = os.WriteFile(exitDial.cachePath, b, 0o600)
	exitDial.mem = nil
	r = runDiag(u, "SECRETTOKEN", "")
	if r.Summary != "ok_via_ipcache" || r.TCP.Source != "cache" || r.IPCache == nil || r.IPCache.IP != "127.0.0.1" {
		t.Fatalf("with cache: %q %+v %+v", r.Summary, r.TCP, r.IPCache)
	}
	exitDial.override = "127.0.0.1"
	srv.Close()
	r = runDiag(u, "SECRETTOKEN", "")
	if r.Summary != "tcp_failed" || r.TCP.Err != "connection refused" {
		t.Fatalf("closed port: %q %+v", r.Summary, r.TCP)
	}
}
