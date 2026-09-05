package daemon_test

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/log"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
	"google.golang.org/protobuf/types/known/emptypb"
)

func TestZapretRuntimeLogObserverBeforeCoreStartup(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	service := daemon.NewStartedService(daemon.ServiceOptions{Context: ctx, LogMaxLines: 128})
	defer service.Close()
	listener := bufconn.Listen(1024 * 1024)
	server := grpc.NewServer()
	daemon.RegisterStartedServiceServer(server, service)
	go func() { _ = server.Serve(listener) }()
	defer server.Stop()
	connection, err := grpc.NewClient("passthrough:///runtime-log-test",
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) { return listener.Dial() }))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	client := daemon.NewStartedServiceClient(connection)
	stream, err := client.SubscribeLog(ctx, &emptypb.Empty{})
	if err != nil {
		t.Fatal(err)
	}
	// Mirrors CommandClient.handleLogStream: metadata before the first Recv.
	if _, err = client.GetDefaultLogLevel(ctx, &emptypb.Empty{}); err != nil {
		t.Fatal(err)
	}
	if _, err = stream.Recv(); err != nil {
		t.Fatal(err)
	}
	const original = "synthetic startup error: original details"
	service.WriteMessage(log.LevelError, original)
	message, err := stream.Recv()
	if err != nil {
		t.Fatal(err)
	}
	if len(message.Messages) != 1 || message.Messages[0].Message != original {
		t.Fatalf("startup error missing or rewritten: %v", message)
	}
}
