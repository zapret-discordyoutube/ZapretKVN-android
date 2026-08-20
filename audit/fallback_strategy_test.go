package fallback

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/common/logger"
)

// This file is copied into the exact pinned checkout only for `go test` and is
// removed immediately afterwards. It deliberately tests the unexported
// strategy implementation without patching the libbox binary we ship.
//
// Core 2.6.x semantics under audit (changed from 2.5.x):
//   - every attempt runs in a context DERIVED from the caller's one (values
//     inherited, deadline split per attempt) instead of the identical object;
//   - SERVFAIL/REFUSED are transport failures and fall back to the next
//     server; only NOERROR and NXDOMAIN are final results;
//   - the whole sequential pass never outlives the caller's deadline.
type zapretAuditKey struct{}

type zapretAuditTransport struct {
	tag      string
	calls    int
	exchange func(context.Context, *dns.Msg) (*dns.Msg, error)
}

func (t *zapretAuditTransport) Start(adapter.StartStage) error { return nil }
func (t *zapretAuditTransport) Close() error                   { return nil }
func (t *zapretAuditTransport) Type() string                   { return "audit" }
func (t *zapretAuditTransport) Tag() string                    { return t.tag }
func (t *zapretAuditTransport) Dependencies() []string         { return nil }
func (t *zapretAuditTransport) Reset()                         {}
func (t *zapretAuditTransport) Exchange(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	t.calls++
	return t.exchange(ctx, message)
}

func zapretRequireInherited(t *testing.T, ctx context.Context, who string) {
	t.Helper()
	if ctx.Value(zapretAuditKey{}) != "shared" {
		t.Fatalf("%s transport lost the caller's context values", who)
	}
	if _, ok := ctx.Deadline(); !ok {
		t.Fatalf("%s transport received a context without a deadline", who)
	}
}

func TestZapretSequentialSuccessStopsAtFirstTransportAndKeepsContext(t *testing.T) {
	shared := context.WithValue(context.Background(), zapretAuditKey{}, "shared")
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	want := new(dns.Msg).SetReply(query)
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		zapretRequireInherited(t, ctx, "first")
		return want, nil
	}
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = func(context.Context, *dns.Msg) (*dns.Msg, error) {
		t.Fatal("second transport ran after first success")
		return nil, nil
	}

	strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, logger.NOP(), 0)
	if err != nil {
		t.Fatal(err)
	}
	response, err := strategy(shared, query)
	if err != nil || response != want {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if first.calls != 1 || second.calls != 0 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialTransportErrorFallsBackWithInheritedContext(t *testing.T) {
	shared := context.WithValue(context.Background(), zapretAuditKey{}, "shared")
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	want := new(dns.Msg).SetReply(query)
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		zapretRequireInherited(t, ctx, "first")
		return nil, errors.New("transport failed")
	}
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
		zapretRequireInherited(t, ctx, "fallback")
		return want, nil
	}

	strategy, err := CreateStrategy("", []adapter.DNSTransport{first, second}, logger.NOP(), 0)
	if err != nil {
		t.Fatal(err)
	}
	response, err := strategy(shared, query)
	if err != nil || response != want {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if first.calls != 1 || second.calls != 1 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialHangNeverOutlivesSharedDeadline(t *testing.T) {
	const budget = 80 * time.Millisecond
	sharedTimeout, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()
	shared := context.WithValue(sharedTimeout, zapretAuditKey{}, "shared")
	sharedDeadline, _ := sharedTimeout.Deadline()
	query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
	hang := func(who string) func(context.Context, *dns.Msg) (*dns.Msg, error) {
		return func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
			zapretRequireInherited(t, ctx, who)
			deadline, _ := ctx.Deadline()
			if deadline.After(sharedDeadline) {
				t.Fatalf("%s transport got a deadline past the caller's one", who)
			}
			<-ctx.Done()
			return nil, ctx.Err()
		}
	}
	first := &zapretAuditTransport{tag: "first"}
	first.exchange = hang("first")
	second := &zapretAuditTransport{tag: "second"}
	second.exchange = hang("fallback")

	strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, logger.NOP(), 0)
	if err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	response, err := strategy(shared, query)
	if response != nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("unexpected result: response=%p error=%v", response, err)
	}
	if elapsed := time.Since(started); elapsed > budget+40*time.Millisecond {
		t.Fatalf("sequential pass outlived the caller's deadline: %v", elapsed)
	}
	if first.calls != 1 || second.calls != 1 {
		t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
	}
}

func TestZapretSequentialFinalRcodesStopWithoutFallback(t *testing.T) {
	for _, rcode := range []int{dns.RcodeSuccess, dns.RcodeNameError} {
		t.Run(dns.RcodeToString[rcode], func(t *testing.T) {
			shared := context.WithValue(context.Background(), zapretAuditKey{}, "shared")
			query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
			want := new(dns.Msg).SetReply(query)
			want.Rcode = rcode
			first := &zapretAuditTransport{tag: "first"}
			first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
				zapretRequireInherited(t, ctx, "first")
				return want, nil
			}
			second := &zapretAuditTransport{tag: "second"}
			second.exchange = func(context.Context, *dns.Msg) (*dns.Msg, error) {
				t.Fatal("second transport ran after a final RCODE response")
				return nil, nil
			}

			strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, logger.NOP(), 0)
			if err != nil {
				t.Fatal(err)
			}
			response, err := strategy(shared, query)
			if err != nil || response == nil || response.Rcode != rcode {
				t.Fatalf("unexpected result: response=%v error=%v", response, err)
			}
			if first.calls != 1 || second.calls != 0 {
				t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
			}
		})
	}
}

func TestZapretSequentialServerFailureRcodesFallBack(t *testing.T) {
	for _, rcode := range []int{dns.RcodeServerFailure, dns.RcodeRefused} {
		t.Run(dns.RcodeToString[rcode], func(t *testing.T) {
			shared := context.WithValue(context.Background(), zapretAuditKey{}, "shared")
			query := new(dns.Msg).SetQuestion("example.com.", dns.TypeA)
			bad := new(dns.Msg).SetReply(query)
			bad.Rcode = rcode
			want := new(dns.Msg).SetReply(query)
			first := &zapretAuditTransport{tag: "first"}
			first.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
				zapretRequireInherited(t, ctx, "first")
				return bad, nil
			}
			second := &zapretAuditTransport{tag: "second"}
			second.exchange = func(ctx context.Context, _ *dns.Msg) (*dns.Msg, error) {
				zapretRequireInherited(t, ctx, "fallback")
				return want, nil
			}

			strategy, err := CreateStrategy("sequential", []adapter.DNSTransport{first, second}, logger.NOP(), 0)
			if err != nil {
				t.Fatal(err)
			}
			response, err := strategy(shared, query)
			if err != nil || response != want {
				t.Fatalf("unexpected result: response=%v error=%v", response, err)
			}
			if first.calls != 1 || second.calls != 1 {
				t.Fatalf("unexpected call counts: first=%d second=%d", first.calls, second.calls)
			}
		})
	}
}
