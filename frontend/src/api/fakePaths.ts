// Shared by Vite proxy and browser transport; never grants server permission.
const id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
export function fakeMutationPath(method: string, path: string): boolean {
  if (method === "PUT")
    return new RegExp(`^/api/v1/experiments/${id}$`).test(path);
  return (
    method === "POST" &&
    (path === "/api/v1/experiments" ||
      new RegExp(
        `^/api/v1/experiments/${id}/(validation|dry-run|executions)$`,
      ).test(path) ||
      new RegExp(
        `^/api/v1/experiments/${id}/executions/${id}/(destroy|reports)$`,
      ).test(path))
  );
}
