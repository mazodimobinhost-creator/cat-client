// Cat Client Xray runner.
//
// Starts the official Xray-core (XTLS/Xray-core, MPL-2.0 — compiled unmodified from source in CI)
// from a JSON config and hands every outbound socket to the Android VPN service for protect()
// over a private unix socket (SCM_RIGHTS), so Xray's own traffic never loops back into the tunnel.
//
// This file is copied into the Xray-core source tree at build time (scripts/build-xray-core.sh)
// so it compiles against Xray's own go.mod / go.sum. It is Cat Client code, not part of Xray-core.
package main

import (
	"bytes"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

var protectFailures atomic.Int64

// protectFd sends fd to the app's protect server and waits for its one-byte verdict.
func protectFd(path string, fd int) error {
	conn, err := net.DialTimeout("unix", path, 3*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	uc, ok := conn.(*net.UnixConn)
	if !ok {
		return errors.New("protect socket is not a unix socket")
	}
	_ = uc.SetDeadline(time.Now().Add(3 * time.Second))
	if _, _, err := uc.WriteMsgUnix([]byte{1}, syscall.UnixRights(fd), nil); err != nil {
		return err
	}
	ack := make([]byte, 1)
	if _, err := io.ReadFull(uc, ack); err != nil {
		return err
	}
	if ack[0] != 1 {
		return errors.New("android refused to protect the socket")
	}
	return nil
}

func isLoopback(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	return ip != nil && ip.IsLoopback()
}

func fail(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "cat-xray: "+format+"\n", args...)
	os.Exit(1)
}

func main() {
	configPath := flag.String("config", "", "path of the Xray JSON config")
	protectPath := flag.String("protect", "", "unix socket of the app's protect server (empty = no protect)")
	watchStdin := flag.Bool("watch-stdin", false, "exit when stdin closes (the app process died)")
	testOnly := flag.Bool("test", false, "only validate the config")
	flag.Parse()

	data, err := os.ReadFile(*configPath)
	if err != nil {
		fail("cannot read config: %v", err)
	}

	if *protectPath != "" {
		path := *protectPath
		err := internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
			if isLoopback(address) {
				return nil
			}
			var protectErr error
			if controlErr := c.Control(func(fd uintptr) { protectErr = protectFd(path, int(fd)) }); controlErr != nil {
				return controlErr
			}
			if protectErr != nil && protectFailures.Add(1) <= 5 {
				// Without an established VPN there is nothing to loop into, so a refusal is not fatal.
				fmt.Fprintf(os.Stderr, "cat-xray: protect %s %s: %v\n", network, address, protectErr)
			}
			return nil
		})
		if err != nil {
			fail("cannot register the protect controller: %v", err)
		}
	}

	config, err := core.LoadConfig("json", bytes.NewReader(data))
	if err != nil {
		fail("invalid config: %v", err)
	}
	server, err := core.New(config)
	if err != nil {
		fail("cannot create the Xray instance: %v", err)
	}
	if *testOnly {
		fmt.Fprintln(os.Stderr, "cat-xray: config OK")
		return
	}
	if err := server.Start(); err != nil {
		fail("cannot start: %v", err)
	}
	fmt.Fprintf(os.Stderr, "cat-xray: Xray %s started\n", core.Version())

	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)
	if *watchStdin {
		go func() {
			_, _ = io.Copy(io.Discard, os.Stdin)
			done <- syscall.SIGTERM
		}()
	}
	<-done
	_ = server.Close()
	fmt.Fprintln(os.Stderr, "cat-xray: stopped")
}
