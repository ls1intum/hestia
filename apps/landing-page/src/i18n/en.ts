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
  privacy: {
    title: "Privacy Policy",
    backToHome: "Back to homepage",
    intro:
      "The Technical University of Munich (TUM) takes the protection of personal data seriously " +
      "and relies on secure, encrypted communication (e.g. HTTPS with a TUM certificate, TLS 1.3, " +
      "HSTS). Within the Hestia research and teaching initiative we process personal data on this " +
      "website and in the Hestia tools (ExamLense, LearningGoalHub, Workshopper) in compliance with " +
      "applicable data protection law. Unless stated otherwise, the legal basis is Art. 6(1)(e) " +
      "GDPR in conjunction with Art. 4(1) BayDSG (performance of a task in the public interest); " +
      "Art. 84 of the Bavarian Higher Education Innovation Act (BayHIG) applies in addition. " +
      "Publisher details are additionally available in the Imprint.",
    sections: [
      {
        heading: "Controller",
        body:
          "Prof. Dr. Stephan Krusche\n" +
          "krusche(at)tum.de\n" +
          "+49 89 289 18233\n\n" +
          "Technical University of Munich\n" +
          "Professorship for Applied Education Technologies (CIT – I1)\n" +
          "Postal address: Arcisstrasse 21, 80333 Munich\n" +
          "Telephone: +49-(0)89-289-01\n" +
          "Email: poststelle(at)tum.de",
      },
      {
        heading: "Data Protection Officer",
        body:
          "The Data Protection Officer of the Technical University of Munich\n" +
          "Postal address: Arcisstrasse 21, 80333 Munich\n" +
          "Telephone: 089/289-17052\n" +
          "Email: beauftragter(at)datenschutz.tum.de",
      },
      {
        heading: "Purposes and legal bases of processing",
        body:
          "The purpose of processing is to fulfil the public tasks assigned to us in research and " +
          "teaching and to provide the Hestia tools. Unless stated otherwise, the legal basis is " +
          "Art. 6(1)(e) GDPR in conjunction with Art. 4(1) BayDSG and Art. 84 BayHIG (performance " +
          "of a task in the public interest). For optional features (newsletter, material " +
          "donation, AI-assisted processing) we process data on the basis of your consent " +
          "(Art. 6(1)(a) GDPR).",
      },
      {
        heading: "Recipients of personal data",
        body:
          "The technical operation of the servers takes place within TUM (Professorship for Applied " +
          "Education Technologies; servers under *.aet.cit.tum.de). Backups are performed " +
          "exclusively within the European Union.\n\n" +
          "For the AI-assisted features, content is transmitted to language models – only if you " +
          "actively consent to their use (see “AI-assisted processing”). Further recipients are the " +
          "newsletter service (Listmonk) and the material storage (Nextcloud), each on TUM " +
          "infrastructure. No transfer to other third parties takes place unless required by law.",
      },
      {
        heading: "Transfer to third countries",
        body:
          "Where the AI-assisted features use providers that process data in the USA (see " +
          "“AI-assisted processing”), personal data is transferred to a third country. The transfer " +
          "is safeguarded by appropriate guarantees within the meaning of Art. 44 et seq. GDPR – in " +
          "particular an adequacy decision of the European Commission (EU-U.S. Data Privacy " +
          "Framework) where the provider is certified, otherwise EU standard contractual clauses. " +
          "Access by authorities in third countries cannot be entirely ruled out.",
      },
      {
        heading: "Storage period",
        body:
          "Personal data is stored only as long as necessary to fulfil our tasks, taking statutory " +
          "retention periods into account. Account data is stored for the duration of use and " +
          "deleted on request. Server log files are automatically deleted after 90 days (see " +
          "“Logging”).",
      },
      {
        heading: "Your rights",
        body:
          "As a data subject you have the following rights:\n\n" +
          "• Access (Art. 15 GDPR)\n" +
          "• Rectification (Art. 16 GDPR)\n" +
          "• Erasure or restriction of processing (Art. 17, 18 GDPR)\n" +
          "• Data portability (Art. 20 GDPR), where applicable\n" +
          "• Withdrawal of consent with effect for the future (Art. 7(3) GDPR)\n\n" +
          "Where processing is based on Art. 6(1)(e) GDPR, you have the right to object at any time, " +
          "on grounds relating to your particular situation (Art. 21 GDPR). To exercise your " +
          "rights, please use the controller contact details above.",
      },
      {
        heading: "Right to lodge a complaint with the supervisory authority",
        body:
          "You have the right to lodge a complaint with the Bavarian State Commissioner for Data " +
          "Protection:\n\n" +
          "Postal address: Postfach 22 12 19, 80502 Munich\n" +
          "Address: Wagmüllerstrasse 18, 80538 Munich\n" +
          "Telephone: 089 212672-0\n" +
          "Email: poststelle(at)datenschutz-bayern.de\n" +
          "https://www.datenschutz-bayern.de/",
      },
      {
        heading: "Technical implementation",
        body:
          "The Hestia web servers are operated within TUM (Professorship for Applied Education " +
          "Technologies). The personal data you transmit when visiting is processed on these " +
          "servers.",
      },
      {
        heading: "Logging",
        body:
          "When you access our web pages, your browser transmits data to our web servers, which is " +
          "temporarily recorded in a log file:\n\n" +
          "• IP address of the requesting computer\n" +
          "• Date and time of access\n" +
          "• Name, URL and amount of data transferred of the retrieved file\n" +
          "• Access status (e.g. transferred, not found)\n" +
          "• Browser and operating system identification data (if transmitted)\n" +
          "• Referrer website (if transmitted)\n\n" +
          "The analysis serves to detect and defend against attacks and for error analysis. " +
          "Storage period: log files are automatically deleted after 90 days, unless needed to " +
          "investigate a specific security incident.",
      },
      {
        heading: "Cookies and local storage",
        body:
          "Hestia uses only technically necessary cookies and local storage:\n\n" +
          "• Login/session cookie: to maintain your login session; deleted after logout or " +
          "expiry.\n" +
          "• Language and theme preference: stored in your browser’s local storage (localStorage: " +
          "hestia-language, hestia-theme).\n\n" +
          "No cookies are used for tracking or advertising. Technically necessary cookies do not " +
          "require consent (§ 25 (2) no. 2 TDDDG).",
      },
      {
        heading: "Fonts",
        body:
          "The fonts used are served locally from our own server (self-hosting). No connection is " +
          "made to external providers such as Google Fonts, so your IP address is not transmitted " +
          "to third parties.",
      },
      {
        heading: "Sign-in (Shibboleth / DFN-AAI)",
        body:
          "The Hestia tools are publicly reachable; using them requires signing in. Sign-in is " +
          "handled via Shibboleth within the higher-education federation DFN-AAI. This lets members " +
          "of TUM as well as members of other participating institutions sign in with their home " +
          "credentials.\n\n" +
          "Authentication takes place exclusively at your home institution; we never learn your " +
          "password. Depending on its configuration, your home institution transmits the attributes " +
          "required for operation, in particular a (possibly pseudonymous) user identifier, name, " +
          "email address, and your institution and role. From these we create a user account so we " +
          "can associate your content with you.\n\n" +
          "Storage period: account data is stored for the duration of use and deleted on request, " +
          "unless statutory retention periods apply.",
      },
      {
        heading: "Processing of your content in the tools",
        body:
          "In the tools you upload or enter content:\n\n" +
          "• ExamLense: uploaded exams (e.g. PDF) and the tasks, model solutions, and grades " +
          "derived from them.\n" +
          "• LearningGoalHub: uploaded teaching materials (e.g. slides, module descriptions) and " +
          "the learning goals extracted from them.\n" +
          "• Workshopper: entered learning goals and parameters, uploaded materials, and the " +
          "generated handouts and slides.\n\n" +
          "This content is stored on TUM servers and – after your consent – transmitted for " +
          "AI-assisted processing (see “AI-assisted processing”). Please do not upload personal " +
          "data of third parties (e.g. names or student ID numbers) or confidential content unless " +
          "necessary; anonymise or redact such information beforehand.",
      },
      {
        heading: "AI-assisted processing",
        body:
          "The tools offer AI-assisted features. These are only used if you actively consent to " +
          "their use (opt-in, Art. 6(1)(a) GDPR). Without your consent no AI-assisted processing " +
          "takes place; the corresponding features are then unavailable. The content required for " +
          "the respective request is transmitted to language models:\n\n" +
          "a) Within the EU: open-weight models via the SAIA / “Chat AI” service of GWDG " +
          "(Gesellschaft für wissenschaftliche Datenverarbeitung mbH Göttingen), " +
          "chat-ai.academiccloud.de, on servers in Germany under a data-processing agreement " +
          "(Art. 28 GDPR).\n\n" +
          "b) External, commercial providers with transfer to the USA:\n" +
          "• Google (Gemini) – Google Ireland Ltd. / Google LLC, USA\n" +
          "• OpenAI (ChatGPT) – OpenAI, USA\n" +
          "• Anthropic (Claude) – Anthropic PBC, USA\n\n" +
          "For safeguards on the third-country transfer, see “Transfer to third countries”. " +
          "Depending on the provider and plan, it cannot be ruled out that transmitted content is " +
          "processed further. Therefore do not transmit confidential or personal data that is not " +
          "necessary for the request.",
      },
      {
        heading: "Newsletter",
        body:
          "For the newsletter you provide your email address. We use the double opt-in procedure: " +
          "after signing up you receive a confirmation email; only after confirming do we add you " +
          "to the list. Sending is handled via the Listmonk software on TUM infrastructure. Legal " +
          "basis: your consent (Art. 6(1)(a) GDPR). You can unsubscribe at any time via the link in " +
          "every email and withdraw your consent with effect for the future.",
      },
      {
        heading: "Donating material",
        body:
          "You may voluntarily provide us with teaching materials for research. Uploads go to a " +
          "Nextcloud instance on TUM infrastructure; no login is required. You decide yourself " +
          "which materials to share. Publication in the sense of open science only takes place with " +
          "your consent. Legal basis: your consent (Art. 6(1)(a) GDPR). Please do not upload " +
          "unnecessary personal data of third parties.",
      },
      {
        heading: "Contact by email",
        body:
          "If you contact us by email (e.g. to request an “exam check”), we process the data you " +
          "provide to handle your request. Legal basis: Art. 6(1)(e) GDPR or your consent " +
          "(Art. 6(1)(a) GDPR). The data is deleted once it is no longer needed.",
      },
      {
        heading: "Changes to this privacy policy",
        body:
          "As Hestia is under active development, this privacy policy may be adapted to reflect " +
          "changed features or legal requirements. The version published here at any given time " +
          "applies.\n\n" +
          "Last updated: July 2026",
      },
    ],
  },
};
