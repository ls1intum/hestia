# Activities

## 1. Group Discussion
**🗣️ Analyze the Impact of Regularization**

### 📝 The Prompt
Discuss how introducing an L2 penalty changes the shape of the loss landscape. Provide two concrete examples of when you would prefer this over an unregularized model.

### ⏱️ Logistics
- Form groups of 4.
- Assign one **Note-Taker** and one **Speaker**.
- You have **8 minutes** to discuss.

### 🎯 Deliverable
The Speaker must be prepared to share your group's two examples and explain the reasoning to the room.

---

## 2. Role Play
**🎭 Explaining Models to Stakeholders**

### 🌍 The Scenario
A machine learning model just denied a loan application. The customer is upset and wants to know why. The Data Scientist must explain the logistic regression decision boundary without using heavy mathematical jargon.

### 👤 Role A: Data Scientist
Your goal is to explain the "weights" as "importance scores".

### 👤 Role B: Customer
Your goal is to ask "Why did my age hurt my chances?"

---

## 3. Quiz / Polls
**✅ Which loss function penalizes outliers most?**

- **A:** Mean Absolute Error (L1)
- **B:** Mean Squared Error (L2)
- **C:** Huber Loss

---

## 4. Peer Review
**🤝 Evaluate the Architecture Diagram**

### 🔄 The Exchange
Swap your proposed ML pipeline diagrams with the person sitting next to you.

### 📋 Evaluation Criteria
- Is data preprocessing explicitly stated?
- Are feature logic & model compatible?
- Is there a clear evaluation metric?

### 💬 Feedback Rules
Provide at least one piece of **constructive criticism** and one **positive reinforcement** on their diagram.

---

## 5. Think-Pair-Share
**🧠 Defining the Decision Boundary**

Given weights W = [0.5, -1.2] and Bias = 0.3, formulate the linear equation and sketch where the boundary lies on a 2D plane.

### 1. Think (2m)
Calculate the equation and sketch the line silently on your own paper.

### 2. Pair (2m)
Compare your sketch with your neighbor. Resolve any discrepancies.

### 3. Share (1m)
We will call on 2 pairs to draw their final boundary on the whiteboard.

---

## 6. Case Study
**🕵️ The "Zillow Offers" Pricing Model**

### 📄 Background
In 2021, Zillow shut down its algorithmic home-buying division after massive losses. The automated valuation model (AVM) failed to predict rapid market shifts, leading the company to overpay for thousands of houses.

### 🔍 Analysis Questions
- What assumptions were built into their training data?
- How does this demonstrate 'concept drift'?

---

## 7. Hands-on Practice
**🛠️ Implement Gradient Descent in Python**

### 💻 The Task
Open `notebook_03.ipynb`. Write the update rule for the weights.

```python
for epoch in range(100):
    y_pred = dot(X, weights)
    error = y_pred - y_true
    # TODO: Write weight update here
```

### ✅ Success Criteria
- The loss should decrease every epoch.
- Final MSE should be < 0.05.
- Raise your hand when your plot converges!

---

## 8. Q&A Session
**🙋 Open Questions & Discussion**

### 💬 Discussion Prompts
- **Q1:** If my data has many corrupted labels, should I use MSE or MAE?
- **Q2:** How do we choose the right learning rate for gradient descent without guessing?
- **Q3:** Why is logistic regression considered a "linear" classifier if the sigmoid curve is non-linear?

### 🙋 Floor is Open
Raise your hand if you have additional questions or need clarification on today's concepts.

---

## 9. Brainstorming
**💡 Features for Predicting Housing Prices**

If you wanted to predict the price of a house in Munich, what data points (features) would you collect?

### 📜 Rules
- **Quantity over Quality:** Shout out anything that comes to mind.
- **No judgement:** We will filter the list later.
- I will capture all ideas on the whiteboard.

---

## Slide CSS Styling

```css
/* HESTIA Style Guide Variables */
:root {
  --hestia-bg:       #e9e5db;
  --hestia-surface:  #ffffff;
  --hestia-primary:  #865c1d;
  --hestia-text:     #2c2420;
  --hestia-border:   color-mix(in srgb, var(--hestia-text) 15%, transparent);
  
  /* Phase Colors */
  --hestia-phase-setup:    #2563EB; 
  --hestia-phase-lecture:  #6D28D9; 
  --hestia-phase-practice: #059669; 
  
  --font-display: 'Playfair Display', Georgia, serif;
  --font-body: 'Inter', system-ui, sans-serif;
}

body {
  background-color: var(--hestia-bg);
  color: var(--hestia-text);
  font-family: var(--font-body);
  margin: 0;
  padding: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 60px;
}

/* Standardized Slide Container */
.slide {
  background: var(--hestia-surface);
  width: 960px;
  height: 540px;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0,0,0,0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
  flex-shrink: 0;
}

/* Borders for different phases */
.border-practice { border-left: 8px solid var(--hestia-phase-practice); }
.border-setup { border-left: 8px solid var(--hestia-phase-setup); }
.border-lecture { border-left: 8px solid var(--hestia-phase-lecture); }

.slide-header {
  padding: 32px 48px 16px; /* Slightly tighter header */
}

.slide-subtitle {
  font-size: 14px;
  color: color-mix(in srgb, var(--hestia-text) 70%, transparent);
  text-transform: uppercase;
  letter-spacing: 1px;
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.slide-title {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  margin: 0;
  color: var(--hestia-text);
  line-height: 1.2;
}

.slide-content {
  padding: 0 48px 32px; /* Slightly tighter footer */
  flex: 1;
  display: flex;
  gap: 20px; /* Reduced from 24px */
  flex-direction: column;
}

/* Generic Card Styling - Made more compact */
.card {
  background: color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface));
  border: 1px solid var(--hestia-border);
  border-radius: 8px;
  padding: 16px 20px; /* Reduced from 24px for a smaller box feel */
  display: flex;
  flex-direction: column;
}

.card-title {
  font-size: 16px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 10px 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* Text Helpers */
.text-practice { color: var(--hestia-phase-practice); }
.text-setup { color: var(--hestia-phase-setup); }
.text-lecture { color: var(--hestia-phase-lecture); }
.text-large { font-size: 18px; line-height: 1.5; font-weight: 500; margin: 0; }

.list-clean {
  margin: 0;
  padding-left: 20px;
  line-height: 1.5;
  font-size: 16px; /* Scaled down slightly to fit compact boxes */
}

/* Grids with tighter gaps */
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; flex: 1; }
.grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 20px; flex: 1; }
.sidebar-layout { display: flex; gap: 20px; flex: 1; }
.sidebar-layout > .main { flex: 2; }
.sidebar-layout > .side { flex: 1; }

/* Poll Option Styling */
.poll-option {
  flex-direction: row; 
  align-items: center; 
  gap: 20px; 
  padding: 16px 24px;
}
.poll-letter {
  background: var(--hestia-phase-setup); 
  color: white; 
  padding: 4px 16px; 
  border-radius: 4px; 
  font-weight: bold; 
  font-size: 20px;
}
```
