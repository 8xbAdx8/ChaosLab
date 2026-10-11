"""Check the built production JAR, not a test-classpath security substitute."""
from pathlib import Path
from zipfile import ZipFile

jar = Path(__file__).resolve().parents[1] / "target/chaoslab-backend-0.0.1-SNAPSHOT.jar"
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    assert not any("LegacyBusinessTestSecurity" in name or "ConsoleBrowserFixture" in name for name in names)
    for name in ("ConsoleSecurityConfiguration", "ConsoleIdentityController", "PasswordHashTool"):
        assert f"BOOT-INF/classes/com/chaoslab/console/security/{name}.class" in names
    assert "Start-Class: com.chaoslab.ChaosLabApplication" in archive.read("META-INF/MANIFEST.MF").decode()
print("Production session/RBAC classes present; test-only permissive/browser fixtures absent.")
