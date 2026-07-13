#!/bin/bash
# Recompile the COMSOL MCP bridge Java helpers against the COMSOL 6.4 jars.
# Run this after editing any file in java/*.java .
set -e
COMSOL="${COMSOL_ROOT:-/home/pengyk/comsol64}"
JAVAC="$COMSOL/java/glnxa64/jre/bin/javac"
HERE="$(cd "$(dirname "$0")" && pwd)"
CLASSES="$HERE/classes"
CP="$(ls "$COMSOL/plugins/"*.jar "$COMSOL/apiplugins/"*.jar 2>/dev/null | tr '\n' ':')$CLASSES"
mkdir -p "$CLASSES"
echo "Compiling helpers -> $CLASSES"
"$JAVAC" -cp "$CP" -d "$CLASSES" "$HERE/java/BridgeUtil.java" "$HERE/java/Inspect.java" \
  "$HERE/java/RunStudy.java" "$HERE/java/GetResult.java" "$HERE/java/ExportJava.java" \
  "$HERE/java/Probe.java" "$HERE/java/Dump.java" "$HERE/java/EvalVolume.java" \
  "$HERE/java/EvalReflection.java" "$HERE/java/ProbeFields.java" \
  "$HERE/java/EvalAggregate.java" \
  "$HERE/java/SetParam.java" \
  "$HERE/java/PhysicsFeature.java" \
  "$HERE/java/MaterialInfo.java" \
  "$HERE/java/ParamSweep.java" \
  "$HERE/java/DispMaterial.java" \
  "$HERE/java/ExportImage.java" \
  "$HERE/java/ExportCutPlane.java"
# remove stray UserBuild*.java/.class left by failed build_model runs
rm -f "$CLASSES"/UserBuild*.java "$CLASSES"/UserBuild*.class "$CLASSES"/UserBuild*.status "$CLASSES"/UserBuild*.recovery 2>/dev/null
echo "Done. Classes:"
ls "$CLASSES"/*.class