// SPDX-License-Identifier: GPL-3.0-or-later

// tun2socks-owner — xjasonlyu/tun2socks v2.6.0 (MIT, see LICENSE.tun2socks) with an optional
// per-flow socket-owner check (package owner). Flags are identical to upstream's main.go plus:
//
//	-owner-socket NAME   abstract unix socket served by the app (OwnerUidServer.kt); enables the check
//	-owner-strict        drop flows when the owner query fails / times out (default: allow)
//	-owner-timeout D     per-query budget (default 100ms)
//	-owner-cache-ttl D   verdict cache per 5-tuple (default 2s)
//	-owner-bypass LIST   ip:port destinations allowed without a query (default: TUN DNS placeholders)
//	-owner-udp=false     check TCP only (escape hatch if UDP owner lookups fail on some kernel)
//
// Without -owner-socket the behaviour is upstream's.
package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"strings"
	"syscall"
	"time"

	"go.uber.org/automaxprocs/maxprocs"
	"gopkg.in/yaml.v3"

	_ "github.com/xjasonlyu/tun2socks/v2/dns"
	"github.com/xjasonlyu/tun2socks/v2/engine"
	"github.com/xjasonlyu/tun2socks/v2/log"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"

	"github.com/myappvpn/tun2socks-owner/owner"
)

const version = "2.6.0-owner1"

var (
	key = new(engine.Key)

	configFile  string
	versionFlag bool

	ownerSocket   string
	ownerStrict   bool
	ownerTimeout  time.Duration
	ownerCacheTTL time.Duration
	ownerBypass   string
	ownerUDP      bool
)

func init() {
	flag.IntVar(&key.Mark, "fwmark", 0, "Set firewall MARK (Linux only)")
	flag.IntVar(&key.MTU, "mtu", 0, "Set device maximum transmission unit (MTU)")
	flag.DurationVar(&key.UDPTimeout, "udp-timeout", 0, "Set timeout for each UDP session")
	flag.StringVar(&configFile, "config", "", "YAML format configuration file")
	flag.StringVar(&key.Device, "device", "", "Use this device [driver://]name")
	flag.StringVar(&key.Interface, "interface", "", "Use network INTERFACE (Linux/MacOS only)")
	flag.StringVar(&key.LogLevel, "loglevel", "info", "Log level [debug|info|warn|error|silent]")
	flag.StringVar(&key.Proxy, "proxy", "", "Use this proxy [protocol://]host[:port]")
	flag.StringVar(&key.RestAPI, "restapi", "", "HTTP statistic server listen address")
	flag.StringVar(&key.TCPSendBufferSize, "tcp-sndbuf", "", "Set TCP send buffer size for netstack")
	flag.StringVar(&key.TCPReceiveBufferSize, "tcp-rcvbuf", "", "Set TCP receive buffer size for netstack")
	flag.BoolVar(&key.TCPModerateReceiveBuffer, "tcp-auto-tuning", false, "Enable TCP receive buffer auto-tuning")
	flag.StringVar(&key.MulticastGroups, "multicast-groups", "", "Set multicast groups, separated by commas")
	flag.StringVar(&key.TUNPreUp, "tun-pre-up", "", "Execute a command before TUN device setup")
	flag.StringVar(&key.TUNPostUp, "tun-post-up", "", "Execute a command after TUN device setup")
	flag.BoolVar(&versionFlag, "version", false, "Show version and then quit")

	flag.StringVar(&ownerSocket, "owner-socket", "", "Abstract unix socket name answering socket-owner queries (enables the owner check)")
	flag.BoolVar(&ownerStrict, "owner-strict", false, "Drop flows when the owner query fails or times out")
	flag.DurationVar(&ownerTimeout, "owner-timeout", 100*time.Millisecond, "Owner query timeout")
	flag.DurationVar(&ownerCacheTTL, "owner-cache-ttl", 2*time.Second, "Owner verdict cache TTL per 5-tuple (negative disables)")
	flag.StringVar(&ownerBypass, "owner-bypass", "172.19.0.2:53,[fdfe:dcba:9876::2]:53", "Comma-separated ip:port destinations allowed without a query")
	flag.BoolVar(&ownerUDP, "owner-udp", true, "Also check UDP sessions (false: TCP only)")
	flag.Parse()
}

func main() {
	maxprocs.Set(maxprocs.Logger(func(string, ...any) {}))

	if versionFlag {
		fmt.Printf("tun2socks-%s\n", version)
		fmt.Printf("%s/%s, %s\n", runtime.GOOS, runtime.GOARCH, runtime.Version())
		os.Exit(0)
	}

	if configFile != "" {
		data, err := os.ReadFile(configFile)
		if err != nil {
			log.Fatalf("Failed to read config file '%s': %v", configFile, err)
		}
		if err = yaml.Unmarshal(data, key); err != nil {
			log.Fatalf("Failed to unmarshal config file '%s': %v", configFile, err)
		}
	}

	// A fresh global tunnel whose processing loop is NOT started yet: the engine dials through
	// tunnel.T() and hands it to the stack as the transport handler, so flows arriving before
	// the owner dialer is installed block in HandleTCP/HandleUDP instead of being proxied
	// unchecked. Upstream starts the loop in tunnel's init(); the old instance just idles.
	t := tunnel.New(&proxy.Base{}, statistic.DefaultManager)
	tunnel.ReplaceGlobal(t)

	engine.Insert(key)
	engine.Start()
	defer engine.Stop()

	if ownerSocket != "" {
		bypass, err := owner.ParseBypass(ownerBypass)
		if err != nil {
			log.Fatalf("invalid -owner-bypass: %v", err)
		}
		addr := ownerSocket
		if !strings.HasPrefix(addr, "@") && !strings.HasPrefix(addr, "/") {
			addr = "@" + addr // abstract namespace
		}
		checker := owner.New(owner.Config{
			Address:  addr,
			Timeout:  ownerTimeout,
			CacheTTL: ownerCacheTTL,
			Strict:   ownerStrict,
			Bypass:   bypass,
			SkipUDP:  !ownerUDP,
			Logf:     log.Warnf,
		})
		defer checker.Close()
		t.SetDialer(&owner.Dialer{Dialer: t.Dialer(), C: checker})
		log.Infof("[OWNER] socket-owner check via %s (strict=%v, timeout=%s)", addr, ownerStrict, ownerTimeout)
	}
	t.ProcessAsync()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
}
