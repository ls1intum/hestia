import { useEffect, useState } from "react";
import { ThemeProvider } from "@/hooks/use-theme";
import { LanguageProvider } from "@/hooks/use-language";
import { SiteHeader } from "@/components/SiteHeader";
import { Hero } from "@/components/Hero";
import { VisionSection } from "@/components/VisionSection";
import { PipelineSection } from "@/components/PipelineSection";
import { MaterialSection } from "@/components/MaterialSection";
import { ImpressumPage } from "@/components/ImpressumPage";
import { DatenschutzPage } from "@/components/DatenschutzPage";
import { SiteFooter } from "@/components/SiteFooter";
import { TestSystemBanner } from "@/components/TestSystemBanner";

/**
 * Minimal hash routing — no router dependency. The real pages besides the landing
 * content are the Impressum (`#/impressum`) and the privacy policy (`#/datenschutz`).
 * A slashed hash avoids colliding with the in-page scroll anchors (`#top`,
 * `#newsletter`, `#material`).
 */
function useHashRoute() {
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const onChange = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return hash;
}

const App = () => {
  const hash = useHashRoute();
  const isImprint = hash === "#/impressum";
  const isPrivacy = hash === "#/datenschutz";

  useEffect(() => {
    if (isImprint || isPrivacy) window.scrollTo(0, 0);
  }, [isImprint, isPrivacy]);

  return (
    <LanguageProvider>
      <ThemeProvider>
        <TestSystemBanner />
        <div className="min-h-screen bg-hestia-bg font-body text-hestia-text">
          <SiteHeader />
          <main>
            {isImprint ? (
              <ImpressumPage />
            ) : isPrivacy ? (
              <DatenschutzPage />
            ) : (
              <>
                <Hero />
                <VisionSection />
                <PipelineSection />
                <MaterialSection />
              </>
            )}
          </main>
          <SiteFooter />
        </div>
      </ThemeProvider>
    </LanguageProvider>
  );
};

export default App;
