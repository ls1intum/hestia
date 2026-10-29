import { Link } from "react-router-dom";

/** Shown for an unknown address or a course that does not exist (any more). */
export default function NotFoundPage({ message = "This page does not exist." }: { message?: string }) {
  return (
    <div className="mx-auto flex max-w-5xl flex-col items-start gap-3 py-12">
      <h1 className="text-2xl">Not found</h1>
      <p className="text-sm text-hestia-text-muted">{message}</p>
      <Link to="/" className="text-sm font-semibold text-hestia-primary hover:underline">
        Back to all courses
      </Link>
    </div>
  );
}
