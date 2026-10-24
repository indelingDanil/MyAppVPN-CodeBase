package main

// -diag: why does the exit not work on this network? Runs a fixed sequence of
// bounded tests and returns one JSON report (printed as "OFDIAG <json>"). The
// token never appears in it: URLs are not reported and errors go through errStr.

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// Diagnostic timings (vars so tests can shrink them).
var (
	diagTCPTimeout   = 8 * time.Second
	diagHTTPTimeout  = 12 * time.Second
	diagPreambleWait = 12 * time.Second // request → full 4096-byte preamble
	diagStreamWait   = 9 * time.Second  // preamble → first extra byte (keepalive pad every 5 s)
	diagShortMax     = 20 * time.Second // a short poll must end by then
)

type diagDNS struct {
	Ms   int64    `json:"ms"`
	IPv4 []string `json:"ipv4"`
	IPv6 int      `json:"ipv6"`
	Err  string   `json:"err,omitempty"`
}

type diagCache struct {
	IP       string `json:"ip,omitempty"`
	At       int64  `json:"at,omitempty"`
	Override string `json:"override,omitempty"`
}

type diagTCP struct {
	Target string `json:"target,omitempty"`
	Source string `json:"source,omitempty"` // override | dns | dns6 | cache
	Ms     int64  `json:"ms"`
	Family string `json:"family,omitempty"` // local address family: ipv4 | ipv6
	Err    string `json:"err,omitempty"`
}

type diagHTTP struct {
	Ms      int64             `json:"ms"`
	Status  int               `json:"status"`
	Body    string            `json:"body"`
	Proto   bool              `json:"x_of_proto"`
	Headers map[string]string `json:"headers,omitempty"`
	Err     string            `json:"err,omitempty"`
}

type diagStream struct {
	Status      int    `json:"status"`
	Reason      string `json:"reason,omitempty"`
	Body        string `json:"body,omitempty"`
	HeadersMs   int64  `json:"headers_ms"`  // -1 = none
	PreambleMs  int64  `json:"preamble_ms"` // -1 = not received
	StreamingOK *bool  `json:"streaming_ok,omitempty"`
	TotalMs     int64  `json:"total_ms,omitempty"`
	Bytes       int64  `json:"bytes"`
	Err         string `json:"err,omitempty"`
	session     string
}

type diagUp struct {
	Status int    `json:"status"`
	Body   string `json:"body"`
	Err    string `json:"err,omitempty"`
}

type diagReport struct {
	Host        string      `json:"host"`
	Port        int         `json:"port"`
	Scheme      string      `json:"scheme"`
	Proto       int         `json:"proto"`
	DNS         diagDNS     `json:"dns_system"`
	IPCache     *diagCache  `json:"ipcache"`
	TCP         diagTCP     `json:"tcp"`
	HTTPProbe   diagHTTP    `json:"http_probe"`
	StreamLong  *diagStream `json:"stream_long"`
	StreamShort *diagStream `json:"stream_short"`
	UpPost      *diagUp     `json:"up_post"`
	Summary     string      `json:"summary"`
}

func ms(since time.Time) int64 { return time.Since(since).Milliseconds() }

func clip(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// joinQuery appends query parameters to an endpoint that may already have some.
func joinQuery(endpoint, q string) string {
	if strings.Contains(endpoint, "?") {
		return endpoint + "&" + q
	}
	return endpoint + "?" + q
}

// suspicious response headers that point at a middlebox.
func proxyHeaders(h http.Header) map[string]string {
	out := map[string]string{}
	for k, v := range h {
		ck := http.CanonicalHeaderKey(k)
		switch {
		case ck == "Via", ck == "Server", ck == "Proxy-Connection", ck == "Age",
			strings.HasPrefix(ck, "X-Cache"), strings.HasPrefix(ck, "X-Proxy"),
			strings.HasPrefix(ck, "X-Squid"), ck == "X-Forwarded-For":
			out[ck] = clip(strings.Join(v, ", "), 60)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func runDiag(rawURL, token, client string) *diagReport {
	r := &diagReport{Proto: 2} // only v2 exits exist now (the field stays for the app)
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" {
		r.Summary = "bad_url"
		return r
	}
	r.Host, r.Scheme = u.Hostname(), u.Scheme
	r.Port, _ = strconv.Atoi(u.Port())
	if r.Port == 0 {
		r.Port = 80
		if u.Scheme == "https" {
			r.Port = 443
		}
	}
	d := exitDial

	// a. system DNS
	t0 := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), dnsTimeout)
	addrs, err := d.lookup(ctx, r.Host)
	cancel()
	r.DNS.Ms = ms(t0)
	var v6 []string
	r.DNS.IPv4, v6 = splitIPs(addrs)
	r.DNS.IPv6 = len(v6)
	if r.DNS.IPv4 == nil {
		r.DNS.IPv4 = []string{}
	}
	if err != nil {
		r.DNS.Err = errStr(err)
	} else if len(addrs) == 0 {
		r.DNS.Err = "no addresses"
	}
	if len(r.DNS.IPv4) > 0 {
		d.store(r.Host, r.DNS.IPv4[0])
	}

	// b. IP cache / override
	if e := d.cached(r.Host); e != nil || d.override != "" {
		r.IPCache = &diagCache{Override: d.override}
		if e != nil {
			r.IPCache.IP, r.IPCache.At = e.IP, e.At
		}
	}

	// c. TCP
	switch {
	case d.override != "":
		r.TCP.Target, r.TCP.Source = d.override, "override"
	case len(r.DNS.IPv4) > 0:
		r.TCP.Target, r.TCP.Source = r.DNS.IPv4[0], "dns"
	case r.IPCache != nil && r.IPCache.IP != "":
		r.TCP.Target, r.TCP.Source = r.IPCache.IP, "cache"
	case len(v6) > 0:
		r.TCP.Target, r.TCP.Source = v6[0], "dns6"
	case net.ParseIP(r.Host) != nil:
		r.TCP.Target, r.TCP.Source = r.Host, "literal"
	}
	if r.TCP.Target == "" {
		r.TCP.Err = "no address to dial"
		r.TCP.Ms = -1
	} else {
		t0 = time.Now()
		ctx, cancel = context.WithTimeout(context.Background(), diagTCPTimeout)
		c, err := d.dial(ctx, "tcp", net.JoinHostPort(r.TCP.Target, strconv.Itoa(r.Port)))
		cancel()
		r.TCP.Ms = ms(t0)
		if err != nil {
			r.TCP.Err = errStr(err)
		} else {
			if ta, ok := c.LocalAddr().(*net.TCPAddr); ok {
				r.TCP.Family = "ipv6"
				if ta.IP.To4() != nil {
					r.TCP.Family = "ipv4"
				}
			}
			c.Close()
		}
	}
	if r.TCP.Err != "" {
		r.Summary = diagVerdict(r)
		return r
	}

	hc := &http.Client{Transport: newExitTransport(d)}
	defer hc.CloseIdleConnections()

	// d. HTTP probe with a wrong token
	r.HTTPProbe = diagProbe(hc, rawURL)
	if r.HTTPProbe.Err != "" {
		r.Summary = diagVerdict(r)
		return r
	}

	// e–g. streaming (v2 only)
	if r.Proto == 2 {
		r.StreamLong = diagDown(hc, rawURL, token, client, false)
		if r.StreamLong.Status == http.StatusOK || r.StreamLong.HeadersMs < 0 {
			r.StreamShort = diagDown(hc, rawURL, token, client, true)
		}
		var sess string
		switch {
		case r.StreamShort != nil && r.StreamShort.Status == http.StatusOK:
			sess = r.StreamShort.session
		case r.StreamLong.Status == http.StatusOK:
			sess = r.StreamLong.session
		}
		if sess != "" {
			r.UpPost = diagPost(hc, rawURL, token, client, sess)
		}
	}
	r.Summary = diagVerdict(r)
	return r
}

func diagProbe(hc *http.Client, rawURL string) diagHTTP {
	var p diagHTTP
	ctx, cancel := context.WithTimeout(context.Background(), diagHTTPTimeout)
	defer cancel()
	t0 := time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, joinQuery(rawURL, "r=down&k=WRONG-TOKEN-xxxxxxxx&s=diagprobe01&v=2"), nil)
	if err != nil {
		p.Err = errStr(err)
		return p
	}
	resp, err := hc.Do(req)
	if err != nil {
		p.Ms, p.Err = ms(t0), errStr(err)
		return p
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 32))
	p.Ms, p.Status, p.Body = ms(t0), resp.StatusCode, string(body)
	p.Proto = resp.Header.Get("X-OF-Proto") != ""
	p.Headers = proxyHeaders(resp.Header)
	return p
}

// diagDown opens a new v2 session with one down poll (long or short) and
// measures how its bytes arrive.
func diagDown(hc *http.Client, rawURL, token, client string, short bool) *diagStream {
	st := &diagStream{HeadersMs: -1, PreambleMs: -1, session: newSessionID()}
	q := "k=" + url.QueryEscape(token) + "&s=" + st.session
	if client != "" {
		q += "&c=" + url.QueryEscape(client)
	}
	q += "&v=2&r=down&from=0"
	if short {
		q += "&poll=short"
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var timedOut atomic.Bool
	wd := time.AfterFunc(diagPreambleWait, func() { timedOut.Store(true); cancel() })
	defer wd.Stop()
	t0 := time.Now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, joinQuery(rawURL, q), nil)
	if err != nil {
		st.Err = errStr(err)
		return st
	}
	resp, err := hc.Do(req)
	if err != nil {
		st.Err = errStr(err)
		if timedOut.Load() {
			st.Err = "no headers (timeout)"
		}
		return st
	}
	defer resp.Body.Close()
	st.HeadersMs, st.Status = ms(t0), resp.StatusCode
	if resp.StatusCode != http.StatusOK {
		st.Reason = resp.Header.Get("X-OF-Reason")
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 32))
		st.Body = strings.TrimSpace(string(b))
		return st
	}
	pre := make([]byte, v2PreambleLen)
	n, err := io.ReadFull(resp.Body, pre)
	st.Bytes = int64(n)
	if err != nil {
		st.Err = "preamble: " + errStr(err)
		if timedOut.Load() {
			st.Err = "preamble timeout"
		}
		f := false
		st.StreamingOK = nil
		if !short {
			st.StreamingOK = &f
		}
		st.TotalMs = ms(t0)
		return st
	}
	st.PreambleMs = ms(t0)
	if _, ok := parsePreamble(pre); !ok {
		st.Err = "bad preamble " + strconv.Quote(clip(string(bytes.TrimSpace(pre)), 24))
	}
	buf := make([]byte, 32<<10)
	if !short {
		wd.Reset(diagStreamWait)
		k, err := resp.Body.Read(buf)
		st.Bytes += int64(k)
		ok := k > 0
		st.StreamingOK = &ok
		if !ok && err != nil && st.Err == "" {
			st.Err = "after preamble: " + errStr(err)
			if timedOut.Load() {
				st.Err = "no keepalive within " + diagStreamWait.String()
			}
		}
		st.TotalMs = ms(t0)
		return st
	}
	wd.Reset(diagShortMax - time.Since(t0))
	for {
		k, err := resp.Body.Read(buf)
		st.Bytes += int64(k)
		if err == io.EOF {
			break
		}
		if err != nil {
			if st.Err == "" {
				st.Err = errStr(err)
				if timedOut.Load() {
					st.Err = "short poll did not end within " + diagShortMax.String()
				}
			}
			break
		}
	}
	st.TotalMs = ms(t0)
	return st
}

func diagPost(hc *http.Client, rawURL, token, client, sess string) *diagUp {
	p := &diagUp{}
	q := "k=" + url.QueryEscape(token) + "&s=" + sess
	if client != "" {
		q += "&c=" + url.QueryEscape(client)
	}
	q += "&v=2&r=up&off=0"
	ctx, cancel := context.WithTimeout(context.Background(), diagHTTPTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, joinQuery(rawURL, q), bytes.NewReader(nil))
	if err != nil {
		p.Err = errStr(err)
		return p
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := hc.Do(req)
	if err != nil {
		p.Err = errStr(err)
		return p
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 32))
	p.Status, p.Body = resp.StatusCode, strings.TrimSpace(string(b))
	return p
}

// diagVerdict condenses the report into one machine-readable string.
func diagVerdict(r *diagReport) string {
	dnsBad := r.DNS.Err != "" || len(r.DNS.IPv4) == 0
	switch {
	case r.TCP.Target == "":
		return "dns_failed"
	case r.TCP.Err == "permission denied":
		return "tcp_blocked_permission" // typically the OS denying this app mobile data
	case r.TCP.Err != "":
		return "tcp_failed"
	case r.HTTPProbe.Err != "":
		return "http_failed"
	case r.Proto == 2 && (!r.HTTPProbe.Proto || r.HTTPProbe.Status != http.StatusNotFound):
		return "http_unexpected" // something other than the exit answered (block page, proxy)
	}
	if r.Proto == 2 {
		l, s := r.StreamLong, r.StreamShort
		switch {
		case l == nil:
			return "stream_failed"
		case l.Status == http.StatusNotFound || l.Status == http.StatusForbidden:
			return "token_rejected"
		case l.Status == http.StatusServiceUnavailable:
			reason := l.Reason
			if reason == "" {
				reason = "busy"
			}
			return "demo_refused:" + reason
		case l.Status == http.StatusOK && l.StreamingOK != nil && *l.StreamingOK && l.Err == "":
			// long-poll streams fine
		case s != nil && s.Status == http.StatusOK && s.PreambleMs >= 0 && s.Err == "":
			return "proxy_buffering_use_shortpoll"
		case l.Status != 0 && l.Status != http.StatusOK:
			return "http_" + strconv.Itoa(l.Status)
		default:
			return "stream_failed"
		}
		if up := r.UpPost; up == nil || up.Err != "" || up.Status != http.StatusOK || !strings.HasPrefix(up.Body, "ok ") {
			return "up_failed"
		}
	}
	if dnsBad && r.TCP.Source == "cache" {
		return "ok_via_ipcache"
	}
	return "ok"
}
