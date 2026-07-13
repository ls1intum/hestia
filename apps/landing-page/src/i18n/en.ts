import type { Dictionary } from "./de";

/**
 * English dictionary. Typed as `Dictionary` so TypeScript flags any key that
 * drifts from the German source shape. ` ` is a non-breaking space.
 */
export const en: Dictionary = {
  header: {
    newsletter: "Newsletter",
    donate: "Donate materials",
    languageGroup: "Language",
  },
  hero: {
    title: "Hestia – Good teaching in the age of AI.",
    subtitle:
      "Be the first to get access to an AI tool that makes lesson planning easy.",
    formTitle: "Subscribe to the newsletter",
  },
  newsletter: {
    placeholder: "you@university.edu",
    submit: "Sign up",
    error: "Something went wrong. Please try again.",
    emailLabel: "Email address",
    success: "Almost done – please confirm the link in your email.",
    heroHint: "Early access to everything we build. Unsubscribe anytime.",
    footerHint: "Just try it out first. Unsubscribe anytime.",
  },
  themeToggle: {
    toLight: "Activate light theme",
    toDark: "Activate dark theme",
  },
  vision: {
    eyebrow: "The vision",
    heading: "AI is changing teaching. The question is what we align it to.",
    p1: {
      pre: "Good teaching in the age of AI doesn't mean shutting AI out. It means integrating it so that, by the end of their studies, students ",
      strong: "can do more than before",
      post: " – because they've learned to work with AI and to master what AI cannot do.",
    },
    p2: {
      pre: "Hestia helps you set up exactly that: aligning your teaching ",
      strong: "with and to AI",
      post: ". Through Constructive Alignment – learning objectives, teaching activities and assessments deliberately aligned with one another – it becomes visible what AI replaces and which skills remain human.",
    },
    p3: "We're not building a shortcut around good teaching, but tools that make it more robust. Openly developed, research-based and tailored to you.",
  },
  pipeline: {
    eyebrow: "Development pipeline",
    heading: "What we'll offer soon",
    intro: {
      pre: "Three tools we're currently building – all following the same principle: consistently aligning interactive teaching to clear learning objectives. You'll get exclusive previews through the ",
      link: "newsletter",
      post: ".",
    },
    tools: [
      {
        title: "Create & check exams",
        description:
          "Design exams and test them for AI vulnerability before they're used. We already support you with this today – using your own exams.",
        status: "in pilot",
        ctaLabel: "Book an appointment",
      },
      {
        title: "Extract learning objectives",
        description:
          "Distil clear, assessable learning objectives from module descriptions and materials – as the foundation for everything else.",
        status: "in development",
        ctaLabel: "Hear about it via newsletter",
      },
      {
        title: "Design active teaching",
        description:
          "Design courses that inspire and activate – with activities that pay into the learning objectives instead of running alongside them.",
        status: "up next",
        ctaLabel: "Hear about it via newsletter",
      },
    ],
  },
  material: {
    eyebrow: "Join the research",
    heading: "Donate materials",
    intro:
      "We can only build Hestia on real teaching – and for that we need your materials. Help us develop a tool that genuinely takes work off your hands. What you share is entirely up to you.",
    offerTitle: "Our offer: The exam check",
    offer: {
      pre: "Want to know how vulnerable your exam is to AI? We test it with you in a joint ",
      strong: "live session",
      post: " – and discuss what that means for grading. Free of charge while we're piloting the format.",
    },
    offerCta: "Request a live session",
    materialsTitle: "Slides, module descriptions, lesson prep",
    materialsBody:
      "Slides, module descriptions, lesson prep. Your contribution to our research and to the capabilities Hestia develops.",
    trustTitle: "What happens to your material",
    trustSubtitle: "Before you upload anything.",
    trustPoints: [
      { title: "Anonymous", description: "No login, no names required in the material." },
      {
        title: "Research only",
        description: "Used exclusively to develop Hestia.",
      },
      {
        title: "Open Science",
        description: "We publish our tool and all data, if you agree to it.",
      },
    ],
    dataConcept: { pre: "Details in the ", link: "data concept" },
    uploadCta: "Upload material",
    uploadNote: "You'll be redirected to our Nextcloud.",
  },
  footer: {
    tagline: "Good teaching in the age of AI. An open research initiative.",
    stayUpdated: "Stay up to date",
    copyright: "© 2026 Ben Lenk-Ostendorf",
    imprint: "Imprint",
    privacy: "Privacy",
  },
  banner: {
    testSystem: "Test system",
  },
  imprint: {
    title: "Imprint",
    backToHome: "Back to homepage",
    sections: [
      {
        heading: "Publisher",
        body:
          "Technical University of Munich\n" +
          "Postal address: Arcisstrasse 21, 80333 Munich\n" +
          "Telephone: +49-(0)89-289-01\n" +
          "Fax: +49-(0)89-289-22000\n" +
          "Email: poststelle(at)tum.de",
      },
      {
        heading: "Authorized to represent",
        body:
          "The Technical University of Munich is legally represented by the President Prof. Dr. Thomas F. Hofmann.",
      },
      {
        heading: "VAT identification number",
        body: "DE811193231 (in accordance with § 27a of the German VAT tax act - UStG)",
      },
      {
        heading: "Responsible for content",
        body: "Prof. Dr. Stephan Krusche\nBoltzmannstrasse 3\n85748 Garching",
      },
      {
        heading: "Terms of use",
        body:
          "Texts, images, graphics as well as the design of these Internet pages may be subject to copyright. The following are not protected by copyright according to §5 of copyright law (Urheberrechtsgesetz (UrhG)).\n\n" +
          "Laws, ordinances, official decrees and announcements as well as decisions and officially written guidelines for decisions and other official works that have been published in the official interest for general knowledge, with the restriction that the provisions on prohibition of modification and indication of source in Section 62 (1) to (3) and Section 63 (1) and (2) UrhG apply accordingly.\n\n" +
          "As a private individual, you may use copyrighted material for private and other personal use within the scope of Section 53 UrhG. Any duplication or use of objects such as images, diagrams, sounds or texts in other electronic or printed publications is not permitted without our agreement. This consent will be granted upon request by the person responsible for the content. The reprinting and evaluation of press releases and speeches are generally permitted with reference to the source. Furthermore, texts, images, graphics and other files may be subject in whole or in part to the copyright of third parties. The persons responsible for the content will also provide more detailed information on the existence of possible third-party rights.",
      },
      {
        heading: "Liability disclaimer",
        body:
          "The information provided on this website has been collected and verified to the best of our knowledge and belief. However, there will be no warranty that the information provided is up-to-date, correct, complete, and available. There is no contractual relationship with users of this website.\n\n" +
          "We accept no liability for any loss or damage caused by using this website. The exclusion of liability does not apply where the provisions of the German Civil Code (BGB) on liability in case of breach of official duty are applicable (§ 839 of the BGB). We accept no liability for any loss or damage caused by malware when accessing or downloading data or the installation or use of software from this website.\n\n" +
          "Where necessary in individual cases: the exclusion of liability does not apply to information governed by the Directive 2006/123/EC of the European Parliament and of the Council. This information is guaranteed to be accurate and up to date.",
      },
      {
        heading: "Links",
        body:
          "Our own content is to be distinguished from cross-references (“links”) to websites of other providers. These links only provide access for using third-party content in accordance with § 8 of the German telemedia act (TMG). Prior to providing links to other websites, we review third-party content for potential civil or criminal liability. However, a continuous review of third-party content for changes is not possible, and therefore we cannot accept any responsibility. For illegal, incorrect, or incomplete content, including any damage arising from the use or non-use of third-party information, liability rests solely with the provider of the website.",
      },
    ],
  },
};
