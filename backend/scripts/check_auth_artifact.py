"""Check the built production JAR, not a test-classpath security substitute."""
from pathlib import Path
from zipfile import ZipFile
import re

root = Path(__file__).resolve().parents[2]
version = (root / "VERSION").read_text().strip()
assert re.fullmatch(r"\d+\.\d+\.\d+(?:-rc\d+)?", version)
jar = root / "backend/target" / f"chaoslab-backend-{version}.jar"
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    assert not any("LegacyBusinessTestSecurity" in name or "ConsoleBrowserFixture" in name or "FakeDemoBrowserFixture" in name or "DeliveryTestAccounts" in name or "DeliveryIdleFixture" in name for name in names)
    for name in ("ConsoleSecurityConfiguration", "ConsoleIdentityController", "PasswordHashTool"):
        assert f"BOOT-INF/classes/com/chaoslab/console/security/{name}.class" in names
    assert "Start-Class: com.chaoslab.ChaosLabApplication" in archive.read("META-INF/MANIFEST.MF").decode()
print("Production session/RBAC classes present; test-only permissive/browser fixtures absent.")
