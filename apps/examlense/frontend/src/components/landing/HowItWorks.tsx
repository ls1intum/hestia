const STEPS = [
  {
    title: "Paste your exam",
    body: "Drop in the full text of an existing exam. ExamLense parses it into individual tasks automatically.",
  },
  {
    title: "Enrich the tasks",
    body: "Review the parsed tasks and add metadata like task type, topic, and points.",
  },
  {
    title: "Generate AI solutions",
    body: "Run each task through GPT and Claude in one click.",
  },
  {
    title: "Grade and get insights",
    body: "Score the AI's answers and see which tasks are most vulnerable.",
  },
];

export const HowItWorks = () => {
  return (
    <section
      id="how-it-works"
      className="mx-auto w-full max-w-[1120px] px-hestia-5 py-hestia-8 md:py-hestia-10"
    >
      <div className="mb-hestia-6 max-w-3xl">
        <p className="mb-hestia-3 hestia-eyebrow text-hestia-primary">
          Workflow
        </p>
        <h2 className="text-3xl md:text-[2.5rem] font-bold text-hestia-text">
          Four steps from exam to insight.
        </h2>
      </div>

      <div className="grid grid-cols-1 gap-hestia-6 md:grid-cols-2 lg:grid-cols-4">
        {STEPS.map((step, i) => (
          <article key={i} className="hestia-card flex flex-col">
            <span className="font-display text-4xl font-bold text-hestia-primary">
              {String(i + 1).padStart(2, "0")}
            </span>
            <h3 className="mt-hestia-4 font-sans text-lg font-semibold text-hestia-text">
              {step.title}
            </h3>
            <p className="mt-hestia-2 text-sm leading-normal text-hestia-text-muted">
              {step.body}
            </p>
          </article>
        ))}
      </div>
    </section>
  );
};
