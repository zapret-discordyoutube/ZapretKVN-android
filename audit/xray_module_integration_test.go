package xraycore_test

import (
	"context"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
)

func startBox(t *testing.T, text string) *box.Box {
	t.Helper()
	ctx := include.Context(context.Background())
	var options option.Options
	if err := json.UnmarshalContext(ctx, []byte(text), &options); err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	return instance
}

func TestZapretXrayModuleCarriesTCPAndUDPAndIsolatesOwners(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
		}
	}()
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, peer, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			_, _ = udp.WriteTo(buffer[:n], peer)
		}
	}()
	reserve, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reserve.Addr().(*net.TCPAddr).Port
	reserve.Close()
	const uuid = "11111111-1111-1111-1111-111111111111"
	startBox(t, fmt.Sprintf(`{
        "log":{"disabled":true},
        "inbounds":[{"type":"vless","listen":"127.0.0.1","listen_port":%d,
            "users":[{"uuid":"%s"}]}],
        "outbounds":[{"type":"direct","tag":"direct"}]
    }`, port, uuid))
	config := fmt.Sprintf(`{
        "log":{"disabled":true},
        "dns":{"servers":[{"type":"local","tag":"local"}]},
        "outbounds":[{"type":"vless","tag":"proxy","server":"127.0.0.1","server_port":%d,
            "uri":"vless://%s@127.0.0.1:%d?security=none&encryption=none&type=tcp"}]
    }`, port, uuid, port)
	clients := []*box.Box{startBox(t, config), startBox(t, config)}
	for index, client := range clients {
		outbound, loaded := client.Outbound().Outbound("proxy")
		if !loaded {
			t.Fatal("outbound missing")
		}
		if fmt.Sprintf("%T", outbound) != "*xraycore.Outbound" {
			t.Fatalf("wrong protocol owner: %T", outbound)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(listener.Addr().String()))
		if err != nil {
			t.Fatal(err)
		}
		_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
		if _, err = conn.Write([]byte("tcp-echo")); err != nil {
			t.Fatal(err)
		}
		reply := make([]byte, 8)
		if _, err = io.ReadFull(conn, reply); err != nil {
			t.Fatal(err)
		}
		conn.Close()
		if string(reply) != "tcp-echo" {
			t.Fatal("TCP payload changed")
		}
		packet, err := outbound.ListenPacket(ctx, M.ParseSocksaddr(udp.LocalAddr().String()))
		if err != nil {
			t.Fatal(err)
		}
		if _, err = packet.WriteTo([]byte("udp-echo"), udp.LocalAddr()); err != nil {
			t.Fatal(err)
		}
		result := make(chan error, 1)
		go func() {
			data := make([]byte, 32)
			n, peer, err := packet.ReadFrom(data)
			if err == nil && (string(data[:n]) != "udp-echo" || peer.String() != udp.LocalAddr().String()) {
				err = fmt.Errorf("UDP payload or peer changed")
			}
			result <- err
		}()
		select {
		case err = <-result:
			if err != nil {
				t.Fatal(err)
			}
		case <-ctx.Done():
			packet.Close()
			t.Fatal("UDP response timed out")
		}
		packet.Close()
		if index == 0 {
			idlePacket, err := outbound.ListenPacket(ctx, M.ParseSocksaddr(udp.LocalAddr().String()))
			if err != nil {
				t.Fatal(err)
			}
			defer idlePacket.Close()
			if err = client.Close(); err != nil {
				t.Fatal(err)
			}
			closed := make(chan error, 1)
			go func() { _, _, err := idlePacket.ReadFrom(make([]byte, 32)); closed <- err }()
			select {
			case err = <-closed:
				if err == nil {
					t.Fatal("idle packet connection survived owner close")
				}
			case <-time.After(time.Second):
				t.Fatal("idle packet read blocked after owner close")
			}
			if _, err = outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(listener.Addr().String())); err == nil {
				t.Fatal("closed owner accepted new traffic")
			}
		}
	}
}
