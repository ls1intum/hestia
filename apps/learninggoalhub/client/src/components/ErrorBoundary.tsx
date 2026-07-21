import { Component, type ReactNode } from "react";

/**
 * Catches render errors and failed lazy-chunk loads in its subtree so a single broken panel
 * shows a fallback instead of unmounting the whole app to a blank screen. Remounts (clearing
 * the error) whenever `resetKey` changes, so reopening a different source retries cleanly.
 */
export default class ErrorBoundary extends Component<
  { resetKey?: unknown; fallback: ReactNode; children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidUpdate(prev: { resetKey?: unknown }) {
    if (prev.resetKey !== this.props.resetKey && this.state.failed) {
      this.setState({ failed: false });
    }
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}
