def median:
  sort as $s | ($s|length) as $n |
  if $n == 0 then null
  elif ($n % 2) == 1 then $s[($n/2|floor)]
  else (($s[$n/2-1] + $s[$n/2]) / 2)
  end;

def stats($name):
  [ .[] | select(.scenario == $name) ] as $rows |
  {
    scenario:$name,
    repetitions:($rows|length),
    median_cpu_ticks:([$rows[].cpu_ticks]|median),
    median_elapsed_ms:([$rows[].elapsed_nanos / 1000000]|median),
    median_pss_kb:([$rows[].pss_kb]|median),
    median_rss_kb:([$rows[].rss_kb]|median),
    median_tun_bytes:([$rows[].tun_bytes]|median),
    median_uid_network_bytes:([$rows[].uid_network_bytes]|median),
    median_throughput_mbps:([$rows[].throughput_mbps]|median)
  };

def delta($current;$candidate;$metric):
  $current[$metric] as $a | $candidate[$metric] as $b |
  if $a == null or $b == null or $a == 0 then null
  else ((($b-$a)/$a*10000|round)/100)
  end;

def comparison($name;$current;$candidate):
  (stats($current)) as $a | (stats($candidate)) as $b |
  (delta($a;$b;"median_cpu_ticks")) as $cpu |
  (delta($a;$b;"median_elapsed_ms")) as $elapsed |
  (delta($a;$b;"median_pss_kb")) as $pss |
  (delta($a;$b;"median_throughput_mbps")) as $throughput |
  {
    name:$name,current:$current,candidate:$candidate,
    cpu_delta_percent:$cpu,
    elapsed_delta_percent:$elapsed,
    pss_delta_percent:$pss,
    throughput_delta_percent:$throughput,
    decision:(
      if [$cpu,$elapsed,$pss,$throughput] |
        map(select(. != null and (fabs >= $threshold))) |
        length > 0
      then "PHYSICAL_CONFIRMATION_REQUIRED"
      else "NO_CHANGE_BELOW_5_PERCENT_OR_NO_SIGNAL"
      end
    )
  };

. as $rows | [$rows[].scenario] | unique as $names |
{
  significance_percent:$threshold,
  environment:"AVD results are exploratory; energy and production decisions require physical release/profileable runs",
  scenarios:[$names[] as $name | ($rows | stats($name))],
  comparisons:[
    ($rows | comparison("direct_vs_proxy";"selected_direct";"selected_proxy")),
    ($rows | comparison("home_visible_vs_closed";"ui_closed";"ui_home_visible")),
    ($rows | comparison("diagnostics_visible_vs_closed";"ui_closed";"ui_diagnostics_visible")),
    ($rows | comparison("dns_sequential_vs_parallel";"dns_sequential";"dns_parallel")),
    ($rows | comparison("mixed_vs_system";"stack_current_mixed";"stack_system")),
    ($rows | comparison("mtu_default_vs_1500";"mtu_default_9000";"mtu_1500"))
  ]
}
