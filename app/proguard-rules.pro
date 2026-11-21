# Keep rules will be added only for libraries that require them.

# Crash reports (docs/CLIENT_REPORTS.md, «App errors not about a server»): the code of a crash is the exception's
# class name, so R8 must not rename exception classes (the first day of reports had «crash:o82»). Names only:
# unused exception classes are still removed and their members still optimized.
-keepnames class * extends java.lang.Throwable
