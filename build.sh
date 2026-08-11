#!/usr/bin/env bash
# Builds the mcinject agent + injector. No Maven, no Gradle — just javac and jar, because the only
# real dependency (Netty) is already sitting in the Minecraft install we are going to attach to.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---- locate a JDK (the game ships a JRE, which cannot compile) --------------
# Needs JDK 21+. Version-agnostic and works on macOS ("Contents/Home" layout) and Linux (JDK root).
if [[ -n "${MCINJECT_JAVA_HOME:-}" ]]; then
  JAVA_HOME="$MCINJECT_JAVA_HOME"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
  : # already set and usable
elif JH="$(/usr/libexec/java_home 2>/dev/null)" && [[ -x "$JH/bin/javac" ]]; then
  JAVA_HOME="$JH"   # macOS only
elif JH="$(ls -d "$HOME"/.mcinject/jdk/*/Contents/Home "$HOME"/.mcinject/jdk/* 2>/dev/null | while read -r d; do [[ -x "$d/bin/javac" ]] && echo "$d" && break; done)" && [[ -n "$JH" ]]; then
  JAVA_HOME="$JH"
elif command -v javac >/dev/null 2>&1; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
else
  os="$(uname -s)"; arch="$(uname -m)"
  case "$os" in Darwin) plat=mac ;; Linux) plat=linux ;; *) plat=mac ;; esac
  case "$arch" in arm64|aarch64) a=aarch64 ;; *) a=x64 ;; esac
  echo "No JDK found (need JDK 21+). Set JAVA_HOME or MCINJECT_JAVA_HOME, or install one:" >&2
  echo "  mkdir -p ~/.mcinject/jdk && cd ~/.mcinject/jdk && \\" >&2
  echo "  curl -L 'https://api.adoptium.net/v3/binary/latest/25/ga/$plat/$a/jdk/hotspot/normal/eclipse' | tar xz" >&2
  exit 1
fi
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
echo "==> JDK: $JAVA_HOME"

# ---- locate Netty jars to compile the tap against --------------------------
# Any Netty 4.x works: the tap only touches ChannelDuplexHandler, which has been stable for years.
find_netty() {
  local roots=(
    # macOS launchers
    "$HOME/Library/Application Support/ModrinthApp/meta/libraries/io/netty"
    "$HOME/Library/Application Support/minecraft/libraries/io/netty"
    "$HOME/Library/Application Support/PrismLauncher/libraries/io/netty"
    "$HOME/Library/Application Support/com.modrinth.theseus/meta/libraries/io/netty"
    # Linux launchers
    "$HOME/.local/share/ModrinthApp/meta/libraries/io/netty"
    "$HOME/.local/share/PrismLauncher/libraries/io/netty"
    "$HOME/.var/app/com.modrinth.ModrinthApp/data/ModrinthApp/meta/libraries/io/netty"
    "$HOME/.minecraft/libraries/io/netty"
    # Windows launchers (under WSL / Git Bash)
    "$APPDATA/.minecraft/libraries/io/netty"
    "$APPDATA/ModrinthApp/meta/libraries/io/netty"
    # this tool's own cache
    "$HOME/.mcinject/libs"
  )
  for r in "${roots[@]}"; do
    [[ -d "$r" ]] || continue
    local jars
    jars="$(find "$r" -name 'netty-transport-4*.jar' -o -name 'netty-common-4*.jar' -o -name 'netty-buffer-4*.jar' 2>/dev/null | grep -v sources | tr '\n' ':')"
    if [[ "$jars" == *netty-transport* && "$jars" == *netty-common* && "$jars" == *netty-buffer* ]]; then
      echo "$jars"
      return 0
    fi
  done
  return 1
}

download_netty() {
  local v="4.1.118.Final" out="$HOME/.mcinject/libs"
  mkdir -p "$out"
  echo "==> No local Netty found; downloading $v to $out" >&2
  for m in netty-transport netty-common netty-buffer; do
    [[ -f "$out/$m-$v.jar" ]] || curl -sSLf -o "$out/$m-$v.jar" \
      "https://repo1.maven.org/maven2/io/netty/$m/$v/$m-$v.jar"
  done
  echo "$out/netty-transport-$v.jar:$out/netty-common-$v.jar:$out/netty-buffer-$v.jar"
}

NETTY_CP="${MCINJECT_NETTY_CP:-$(find_netty || download_netty)}"
echo "==> Netty classpath: $(echo "$NETTY_CP" | tr ':' '\n' | grep -c 'jar') jar(s)"

# ---- build -----------------------------------------------------------------
rm -rf build dist
mkdir -p build/agent build/tap build/boot build/injector dist

echo "==> Compiling the pipeline tap (against Netty)"
"$JAVAC" --release 21 -nowarn -cp "$NETTY_CP" -d build/tap \
  tap/src/io/netty/channel/McInjectTap.java

echo "==> Compiling the agent core"
"$JAVAC" --release 21 -nowarn -d build/agent $(find agent/src -name '*.java')

# The tap's bytecode rides along as a resource; the agent defines it into the game's Netty
# classloader at runtime, which is the only loader that can link it.
mkdir -p build/agent/mcinject
cp build/tap/io/netty/channel/McInjectTap.class build/agent/mcinject/McInjectTap.class

# The core jar is deliberately NOT an agent jar and never joins the system classpath: the bootstrap
# loads it in a fresh URLClassLoader per attach, which is what makes re-attach a real hot-reload.
"$JAR" cf dist/mcinject-core.jar -C build/agent .

echo "==> Compiling the bootstrap (the only permanently-loaded class)"
"$JAVAC" --release 21 -nowarn -d build/boot $(find boot/src -name '*.java')
cat > build/agent-manifest.txt <<'EOF'
Manifest-Version: 1.0
Agent-Class: dev.mcinject.boot.Boot
Premain-Class: dev.mcinject.boot.Boot
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
Implementation-Title: mcinject-agent
EOF
"$JAR" cfm dist/mcinject-agent.jar build/agent-manifest.txt -C build/boot .

echo "==> Compiling the injector"
"$JAVAC" --release 21 -nowarn -d build/injector $(find injector/src -name '*.java')
cat > build/injector-manifest.txt <<'EOF'
Manifest-Version: 1.0
Main-Class: dev.mcinject.injector.Injector
EOF
"$JAR" cfm dist/mcinject-injector.jar build/injector-manifest.txt -C build/injector .

# ---- launcher scripts ------------------------------------------------------
cat > bin/mcinject-attach <<EOF
#!/usr/bin/env bash
exec "$JAVA_HOME/bin/java" -jar "$ROOT/dist/mcinject-injector.jar" "\$@"
EOF
chmod +x bin/mcinject-attach

echo
echo "Built:"
ls -la dist/
echo
echo "Next:  ./bin/mcinject-attach list"
echo "       ./bin/mcinject-attach attach"
