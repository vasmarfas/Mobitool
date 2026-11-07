#!/usr/bin/env bash
# runs beside a long Kotlin/Native link: every five minutes memory and swap, then CPU, GC counters and the
# compiler stack of every JVM
echo "$(sysctl -n hw.ncpu) cores, $(( $(sysctl -n hw.memsize) / 1048576 ))M memory"
while sleep 300; do
  echo "$(date -u +%H:%M) $(memory_pressure | tail -n 1), swap $(sysctl -n vm.swapusage)"
  # fd 3, so that the JDK tools in the loop do not eat the list on stdin
  while read -r -u 3 pid main; do
    echo "$pid ${main##*.}: $(ps -o %cpu=,rss= -p "$pid" | awk '{ printf "cpu %s%%, rss %dM", $1, $2 / 1024 }')"
    jstat -gcutil "$pid"
    jstack "$pid" | awk 'BEGIN { RS = "" } /at org\.jetbrains\.kotlin\.(backend|fir|ir)|at llvm/ {
      n = split($0, line, "\n")
      for (i = 1; i <= n; i++) if (i <= 20 || line[i] ~ /phase/) print line[i]
      print ""
    }'
  done 3< <(jps -l | grep -v -e Jps -e GradleWrapperMain)
done
