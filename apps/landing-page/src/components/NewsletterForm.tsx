import { FormEvent, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NEWSLETTER_ENDPOINT, NEWSLETTER_LIST_UUID } from "@/config";
import { useI18n } from "@/hooks/use-language";

type NewsletterFormProps = {
  /** hero: large button; footer: compact */
  variant: "hero" | "footer";
};

export function NewsletterForm({ variant }: NewsletterFormProps) {
  const { t } = useI18n();
  const [email, setEmail] = useState("");
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(false);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(false);

    // Listmonk public subscription form. `nonce` is a honeypot that must stay empty for
    // humans. The endpoint doesn't send permissive CORS headers, so we submit no-cors:
    // the request still reaches Listmonk (a simple form POST), we just can't read the
    // opaque response — hence the optimistic success below.
    const body = new URLSearchParams({
      email,
      l: NEWSLETTER_LIST_UUID,
      nonce: "",
    });

    try {
      await fetch(NEWSLETTER_ENDPOINT, {
        method: "POST",
        mode: "no-cors",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body,
      });
      setDone(true);
    } catch {
      setError(true);
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return <Alert>{t.newsletter.success}</Alert>;
  }

  return (
    <>
      <form onSubmit={onSubmit} className="flex flex-wrap items-stretch gap-2">
        <div className={`flex flex-1 ${variant === "hero" ? "min-w-[200px]" : "min-w-[180px]"}`}>
          <Input
            type="email"
            placeholder={t.newsletter.placeholder}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            aria-label={t.newsletter.emailLabel}
          />
        </div>
        <Button type="submit" size={variant === "hero" ? "lg" : "md"} disabled={submitting}>
          {t.newsletter.submit}
        </Button>
      </form>
      {error && (
        <p className="mt-2.5 text-sm font-medium text-hestia-primary" role="alert">
          {t.newsletter.error}
        </p>
      )}
      <p
        className={`text-sm text-hestia-text-muted ${variant === "hero" ? "mt-3 leading-normal" : "mt-2.5"}`}
      >
        {variant === "hero" ? t.newsletter.heroHint : t.newsletter.footerHint}
      </p>
    </>
  );
}
