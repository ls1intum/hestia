import { describe, expect, it } from "vitest";
import { requestError } from "./requestError.ts";

const failed = (status: number, error: unknown) => ({ error, response: new Response(null, { status }) });

describe("requestError", () => {
  it("adds the server's reason for a rejected request", () => {
    const result = failed(400, { code: "BAD_REQUEST", message: "name: size must be between 0 and 255" });
    expect(requestError("Could not rename the course.", result).message).toBe(
      "Could not rename the course. name: size must be between 0 and 255.",
    );
  });

  it("keeps the client's wording alone for a server failure", () => {
    const result = failed(500, { code: "INTERNAL_SERVER_ERROR", message: "Unexpected server error" });
    expect(requestError("Could not rename the course.", result).message).toBe("Could not rename the course.");
  });

  it("keeps the client's wording alone when the body has no reason", () => {
    expect(requestError("Could not delete the course.", failed(404, undefined)).message).toBe(
      "Could not delete the course.",
    );
  });
});
