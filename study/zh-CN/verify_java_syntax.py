#!/usr/bin/env python3
"""仅用现有 JDK 解析 core Java 源码；不下载依赖、不执行注解处理或类型检查。"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA_SOURCE = 'import com.sun.source.util.JavacTask;\nimport java.nio.file.*;\nimport java.util.*;\nimport javax.tools.*;\npublic class ParseJavaSources {\n  public static void main(String[] args) throws Exception {\n    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();\n    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();\n    List<Path> paths;\n    try (var files = Files.walk(Path.of(args[0]))) {\n      paths = files.filter(p -> p.toString().endsWith(".java")).toList();\n    }\n    try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {\n      var units = fm.getJavaFileObjectsFromPaths(paths);\n      JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics, List.of("-proc:none", "-source", "17"), null, units);\n      int count = 0;\n      for (var parsed : task.parse()) count++;\n      int errors = 0;\n      for (var d : diagnostics.getDiagnostics()) {\n        if (d.getKind() == Diagnostic.Kind.ERROR) {System.err.println(d); errors++;}\n      }\n      System.out.println("Parsed " + count + " source files; syntax errors=" + errors + "; no type checking or test execution performed.");\n      if (errors > 0) System.exit(1);\n    }\n  }\n}\n'

with tempfile.TemporaryDirectory(prefix="agentscope-syntax-") as temp:
    source = Path(temp) / "ParseJavaSources.java"
    source.write_text(JAVA_SOURCE)
    result = subprocess.run(["java", str(source), str(ROOT / "agentscope-core/src/main/java")])
    raise SystemExit(result.returncode)
