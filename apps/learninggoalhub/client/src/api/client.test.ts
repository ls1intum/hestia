import { afterEach, describe, expect, it, vi } from "vitest";
import { csrfHeaders, xsrfToken } from "./client.ts";

function setCookie(value: string) {
  vi.stubGlobal("document", { cookie: value });
}

describe("csrf", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reads the token from the XSRF-TOKEN cookie", () => {
    setCookie("a=1; XSRF-TOKEN=abc%3D; b=2");
    expect(xsrfToken()).toBe("abc=");
  });

  it("sends the token on requests that change data, not on reads", () => {
    setCookie("XSRF-TOKEN=tok");
    expect(csrfHeaders("POST")).toEqual({ "X-XSRF-TOKEN": "tok" });
    expect(csrfHeaders("patch")).toEqual({ "X-XSRF-TOKEN": "tok" });
    expect(csrfHeaders("GET")).toEqual({});
  });

  it("sends nothing before the server has set the cookie", () => {
    setCookie("");
    expect(csrfHeaders("POST")).toEqual({});
  });
});
