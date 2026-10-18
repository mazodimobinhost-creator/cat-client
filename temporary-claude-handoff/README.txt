Download all three .part files into one folder, then rebuild the ZIP:

Linux/macOS:
cat claude-handoff-62b9fa5.zip.part-* > claude-handoff-62b9fa5.zip

Windows PowerShell:
Get-Content claude-handoff-62b9fa5.zip.part-* -AsByteStream | Set-Content claude-handoff-62b9fa5.zip -AsByteStream

Verify each downloaded part using SHA256SUMS-parts.txt before rebuilding.
The rebuilt outer ZIP contains the offline repository ZIP and its SHA256SUMS.
