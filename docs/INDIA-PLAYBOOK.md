# Job hunting in India — what actually moves the needle

Written for a mid-level backend engineer (Java/Spring) targeting product
companies and funded startups from India. Opinionated, and about the *strategy*
rather than the tool — the engine exists to remove the typing, not to replace
the judgement.

Where the engine helps, it says so. Where it cannot help, it says that too.

---

## The one thing that matters most

**Referrals beat applications, by a margin that makes everything else a
rounding error.** A referred application at a product company in India is read;
a cold portal application competes with several hundred others and is filtered
by keyword before a person sees it.

So the ordering is:

1. Find the role
2. Find one engineer or hiring manager on that team
3. Message them
4. *Then* apply, mentioning the conversation

Applying first and asking for a referral afterwards is much weaker — the
referral is now paperwork rather than an advocate.

> **Engine:** contacts are attached to a job, and the outreach draft sits next
> to it. It does not find the people — that is a LinkedIn people-search, which
> their terms prohibit and which risks the account you need. Spend the 30
> seconds finding them yourself.

## The screening filters you get rejected by before anyone reads anything

These are the India-specific ones. Getting them wrong costs you the role
regardless of fit.

**Notice period.** The single most common filter. 90 days is genuinely hard to
place for a role that needs someone next month; 30 or "immediate" opens doors.
If you are on 90, find out whether your employer buys it out or negotiates it
down, and say the *negotiated* number, not the contractual one. Never leave it
blank — blank reads as 90.

**Expected CTC.** You will be asked, in a mandatory field, before you speak to
anyone. Two rules:
- Answer in **total annual rupees**, not lakhs, in numeric fields. `2800000`,
  not `28 LPA`. Forms parse them differently and `28` in a rupees field reads as
  twenty-eight rupees.
- Anchor slightly high but not absurd. A 40–60% jump on a genuine role change is
  normal in the Indian market; 200% gets filtered.

**Current CTC.** Asked outright, unlike in the US or EU. Refusing to state it in
a mandatory field usually means the form will not submit. Decide your number and
be consistent across every application — recruiters compare notes more than you
would think.

> **Engine:** all three live in `.work/applicant.json` and are filled
> automatically. The auto-apply guard **refuses to submit** any form asking for
> one you have not stated, rather than guessing a number into a binding field.

## Where the jobs actually are

Ranked by signal-to-noise for a backend engineer:

| Channel | Worth it? |
|---|---|
| **Company careers page direct** | Best. Fewest applicants, fastest path, usually Greenhouse/Lever/Ashby underneath |
| **Referral into a specific team** | Best, if you can get one |
| **LinkedIn jobs** | Good discovery, mediocre application channel — find it here, apply on their site |
| **Instahyre / Cutshort** | Decent for funded product startups; recruiters actually respond |
| **Naukri** | High volume, mostly service companies and staffing. Worth having a *profile* on even if you never apply |
| **Hirist / Wellfound** | Occasional gems, low volume |

**The Naukri exception worth knowing:** recruiters search Naukri's résumé
database far more than they post. An up-to-date Naukri profile is a passive
inbound channel, and the search ranking heavily favours *recently updated*
profiles. Updating it — even trivially — every few days is one of the
highest-return five-minute habits available. This is inbound, not outbound, and
the engine has nothing to do with it.

> **Engine:** sweeps Greenhouse/Lever/Ashby company boards and Adzuna/Remotive/
> RemoteOK/Himalayas automatically. LinkedIn, Naukri and Instahyre come in
> through the **Capture button** — one click on a posting you are already
> reading. See `DECISIONS.md` #23 for why they are not automated.

## Timing

**Apply within 24–48 hours of the posting going up.** Recruiters at product
companies work the queue front-to-back and often stop reading once they have a
shortlist. A perfect application in week three loses to an adequate one on day
one.

This is the strongest argument for the nightly sweep: the engine checks every
watched board each morning, so "posted yesterday" is the default rather than
something you have to catch.

Weekday mornings IST are when Indian recruiters triage. Friday evening
applications sit until Monday and land mid-pile.

## The resume

**One master, several variants, tailored per application.** Not one generic
resume sent everywhere.

- **Keywords matter mechanically.** Most ATS screens rank on term overlap with
  the posting. If the posting says "Spring Boot" and your resume says "Spring",
  you score lower for no good reason. Mirror their vocabulary where it is
  *honestly* true of you.
- **Never invent.** Reordering, re-emphasising and re-wording are fair.
  Employers, titles, dates and skills are not. This is `CLAUDE.md` rule 8 and it
  is a correctness rule, not modesty.
- **PDF, named properly.** `Kousik-V-Backend-Engineer.pdf`, not `resume_v7.pdf`.
- **One page** at under ~6 years of experience.

> **Engine:** the gap list next to every score is exactly the keyword delta —
> what the posting asks for that your profile does not claim. Phase 4 automates
> the tailoring; today it tells you what to tailor.

## Outreach that gets replies

Short, specific, and asking for something small.

What works:
- You have read something concrete about what their team does
- One sentence on why you specifically fit *this* team
- A small ask: "would you be open to a referral?" not "can you get me a job"
- Under 120 words

What does not:
- "I am passionate about your mission"
- A pasted résumé
- Anything that reads as a template sent to fifty people — because it is one

Message engineers on the team over recruiters where you can. They have referral
bonuses and less inbox volume.

## Interview prep, weighted by what Indian product companies actually ask

1. **DSA** — still the first round almost everywhere. LeetCode medium, patterns
   over volume.
2. **Low-level design** — Java/OOP design questions are heavily weighted for
   backend roles here. Practise designing a parking lot, a rate limiter, a
   splitwise.
3. **System design** — from ~4 years up. Know the shape, not every detail.
4. **Your own projects** — the round most people underprepare. Be able to
   explain a real trade-off you made and what you would do differently.

## Numbers to expect

Set expectations so a quiet week does not read as failure:

- Cold portal applications → response rate in the low single digits
- Referred applications → an order of magnitude better
- **20 well-targeted applications with referrals beats 200 cold ones**, and
  takes less total time

This is the whole argument for the engine scoring and ranking rather than
blasting: the constraint is your attention, not the number of forms you can
submit.

> **Engine:** Phase 5 measures your actual response rate by source, role family
> and resume variant, from the `application_event` history recorded from day
> one. After a few hundred applications it replaces these rules of thumb with
> your numbers.

---

## What the engine does not do, and why

- **No LinkedIn automation.** Their terms prohibit it and the account at risk is
  your professional network. `DECISIONS.md` #9 and #23.
- **No finding people for you.** Same reason. You paste a name; it drafts.
- **No sending messages.** It drafts, you send. `DECISIONS.md` #9.
- **No inventing résumé content.** `CLAUDE.md` rule 8.
- **No Naukri profile updates.** No API, and it is inbound anyway.
