import { Navbar } from "@/components/landing/Navbar";
import { Hero } from "@/components/landing/Hero";
import { HowItWorks } from "@/components/landing/HowItWorks";
import { WhyThisMatters } from "@/components/landing/WhyThisMatters";
import { Footer } from "@/components/landing/Footer";

const Index = () => {
  return (
    <div className="min-h-screen bg-hestia-bg pb-16 text-hestia-text">
      <Navbar />
      <main>
        <Hero />
        <HowItWorks />
        <WhyThisMatters />
      </main>
      <Footer />
    </div>
  );
};

export default Index;
