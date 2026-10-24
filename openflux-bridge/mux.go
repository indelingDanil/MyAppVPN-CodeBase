package main

// Stream multiplexer of the OpenFlux v2 exit protocol (see PROTOCOL-v2.md).
//
// Every TCP connection the bridge carries is one stream inside the session's
// two byte streams (down / up). Frames: type(1) | stream id(4, BE) | len(4, BE)
// | payload. The v2 carrier is lossless and ordered in both directions, so the
// mux needs no retransmission, no acks and no per-stream flow control: up
// backpressure comes from the carrier's bounded queue, down backpressure from
// the exit's retention cap.

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"time"
)

const (
	frameOpen    byte = 1 // c→s "host:port"
	frameData    byte = 2 // both: stream bytes
	frameClose   byte = 3 // both: empty
	frameOpenOK  byte = 4 // s→c: empty
	frameOpenErr byte = 5 // s→c: reason text
	frameAck     byte = 6 // c→s: ignored by the v2 exit (never sent by us)

	frameHdr    = 9
	padStreamID = 0xFFFFFFFF // keepalive / alignment frames; never a real stream
	// maxDataFrame: one Write is cut into DATA frames of at most this size, so
	// a single big write cannot hog a whole up POST.
	maxDataFrame = 64 << 10
	// maxInFrame guards against a corrupt down stream (the exit's own cap is 4 MiB).
	maxInFrame = 16 << 20
)

var errSessionClosed = errors.New("of2: session closed")

// encodeFrame appends one frame to dst.
func encodeFrame(dst []byte, typ byte, id uint32, payload []byte) []byte {
	var h [frameHdr]byte
	h[0] = typ
	binary.BigEndian.PutUint32(h[1:5], id)
	binary.BigEndian.PutUint32(h[5:9], uint32(len(payload)))
	dst = append(dst, h[:]...)
	return append(dst, payload...)
}

// carrier moves the session's byte streams (the v2 carrier, or a test fake).
type carrier interface {
	Start() error
	Stop() error
	Send(frame []byte) error   // one whole frame; may block (backpressure)
	Receive(cb func(b []byte)) // down bytes, in order, arbitrary chunking
}

type mux struct {
	car     carrier
	mu      sync.Mutex
	streams map[uint32]*stream
	lastID  uint32
	closed  bool
	done    chan struct{}
	rx      []byte // partial down frame
	bad     bool   // corrupt down stream: ignore everything after
}

func newMux(c carrier) *mux {
	m := &mux{car: c, streams: map[uint32]*stream{}, done: make(chan struct{})}
	c.Receive(m.onBytes)
	return m
}

func (m *mux) Start() error { return m.car.Start() }

// Close ends the session: every stream fails, the carrier stops.
func (m *mux) Close() error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil
	}
	m.closed = true
	close(m.done)
	all := m.streams
	m.streams = map[uint32]*stream{}
	m.mu.Unlock()
	for _, s := range all {
		s.fail(errSessionClosed)
	}
	return m.car.Stop()
}

func (m *mux) send(p []byte) error {
	select {
	case <-m.done:
		return errSessionClosed
	default:
	}
	if err := m.car.Send(p); err != nil {
		return errSessionClosed
	}
	return nil
}

func (m *mux) forget(id uint32) {
	m.mu.Lock()
	delete(m.streams, id)
	m.mu.Unlock()
}

// Dial opens one stream to host:port through the exit and waits for its answer.
func (m *mux) Dial(ctx context.Context, host string, port int) (net.Conn, error) {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, errSessionClosed
	}
	m.lastID++
	if m.lastID == 0 || m.lastID == padStreamID {
		m.lastID = 1
	}
	id := m.lastID
	s := &stream{m: m, id: id, opened: make(chan string, 1), wake: make(chan struct{}, 1)}
	m.streams[id] = s
	m.mu.Unlock()

	if err := m.send(encodeFrame(nil, frameOpen, id, []byte(host+":"+strconv.Itoa(port)))); err != nil {
		m.forget(id)
		return nil, err
	}
	select {
	case reason := <-s.opened:
		if reason != "" {
			m.forget(id)
			return nil, fmt.Errorf("exit refused the stream: %s", reason)
		}
		return s, nil
	case <-ctx.Done():
		m.forget(id)
		s.markRemoteClosed()
		go func() { _ = m.send(encodeFrame(nil, frameClose, id, nil)) }() // the exit may still connect it
		return nil, ctx.Err()
	case <-m.done:
		return nil, errSessionClosed
	}
}

// onBytes parses the down stream (called by the carrier, never concurrently).
func (m *mux) onBytes(b []byte) {
	if m.bad {
		return
	}
	m.rx = append(m.rx, b...)
	i := 0
	for len(m.rx)-i >= frameHdr {
		typ := m.rx[i]
		id := binary.BigEndian.Uint32(m.rx[i+1 : i+5])
		n := int(binary.BigEndian.Uint32(m.rx[i+5 : i+9]))
		if n > maxInFrame {
			m.bad = true
			m.rx = nil
			go m.Close()
			return
		}
		if len(m.rx)-i-frameHdr < n {
			break
		}
		if id != padStreamID {
			m.dispatch(typ, id, m.rx[i+frameHdr:i+frameHdr+n])
		}
		i += frameHdr + n
	}
	if i == len(m.rx) {
		m.rx = m.rx[:0]
		if cap(m.rx) > 1<<20 {
			m.rx = nil
		}
	} else if i > 0 {
		m.rx = append([]byte(nil), m.rx[i:]...)
	}
}

func (m *mux) dispatch(typ byte, id uint32, payload []byte) {
	m.mu.Lock()
	s := m.streams[id]
	m.mu.Unlock()
	if s == nil {
		return
	}
	switch typ {
	case frameOpenOK:
		s.answer("")
	case frameOpenErr:
		r := string(payload)
		if r == "" {
			r = "error"
		}
		s.answer(r)
	case frameData:
		s.push(append([]byte(nil), payload...))
	case frameClose:
		m.forget(id)
		s.markRemoteClosed()
		s.fail(io.EOF)
	}
}

// stream is one carried connection (net.Conn).
type stream struct {
	m      *mux
	id     uint32
	opened chan string
	once   sync.Once

	mu           sync.Mutex
	buf          []byte
	rerr         error // set once no more data will come
	rdl          time.Time
	wake         chan struct{}
	remoteClosed bool
	localClosed  bool
}

func (s *stream) answer(reason string) {
	s.once.Do(func() { s.opened <- reason })
}

func (s *stream) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *stream) push(p []byte) {
	s.mu.Lock()
	if s.rerr == nil {
		s.buf = append(s.buf, p...)
	}
	s.mu.Unlock()
	s.signal()
}

func (s *stream) fail(err error) {
	s.mu.Lock()
	if s.rerr == nil {
		s.rerr = err
	}
	s.mu.Unlock()
	s.signal()
}

func (s *stream) markRemoteClosed() {
	s.mu.Lock()
	s.remoteClosed = true
	s.mu.Unlock()
}

func (s *stream) Read(p []byte) (int, error) {
	for {
		s.mu.Lock()
		if len(s.buf) > 0 {
			n := copy(p, s.buf)
			s.buf = s.buf[n:]
			if len(s.buf) == 0 {
				s.buf = nil
			}
			s.mu.Unlock()
			return n, nil
		}
		if s.rerr != nil {
			err := s.rerr
			s.mu.Unlock()
			return 0, err
		}
		dl := s.rdl
		s.mu.Unlock()
		if dl.IsZero() {
			<-s.wake
			continue
		}
		d := time.Until(dl)
		if d <= 0 {
			return 0, os.ErrDeadlineExceeded
		}
		t := time.NewTimer(d)
		select {
		case <-s.wake:
		case <-t.C:
		}
		t.Stop()
	}
}

func (s *stream) Write(p []byte) (int, error) {
	n := 0
	for len(p) > 0 {
		s.mu.Lock()
		dead := s.localClosed || s.remoteClosed
		s.mu.Unlock()
		if dead {
			return n, net.ErrClosed
		}
		k := min(len(p), maxDataFrame)
		if err := s.m.send(encodeFrame(nil, frameData, s.id, p[:k])); err != nil {
			return n, err
		}
		n += k
		p = p[k:]
	}
	return n, nil
}

func (s *stream) Close() error {
	s.mu.Lock()
	if s.localClosed {
		s.mu.Unlock()
		return nil
	}
	s.localClosed = true
	tell := !s.remoteClosed
	if s.rerr == nil {
		s.rerr = net.ErrClosed
	}
	s.mu.Unlock()
	s.signal()
	s.m.forget(s.id)
	if tell {
		_ = s.m.send(encodeFrame(nil, frameClose, s.id, nil))
	}
	return nil
}

type of2Addr string

func (a of2Addr) Network() string { return "of2" }
func (a of2Addr) String() string  { return string(a) }

func (s *stream) LocalAddr() net.Addr  { return of2Addr("of2-client") }
func (s *stream) RemoteAddr() net.Addr { return of2Addr("of2-exit") }
func (s *stream) SetDeadline(t time.Time) error {
	return s.SetReadDeadline(t)
}
func (s *stream) SetReadDeadline(t time.Time) error {
	s.mu.Lock()
	s.rdl = t
	s.mu.Unlock()
	s.signal()
	return nil
}
func (s *stream) SetWriteDeadline(time.Time) error { return nil }
