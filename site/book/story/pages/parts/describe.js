/*
 * What every story page tells the QA harness about itself, in one place.
 *
 * Eleven archetypes were each writing the same object literal out of the same PagePlan, so a field
 * added to the report had to be added eleven times or it was silently missing from whichever page
 * was forgotten - and a QA check reads as passing when the thing it checks is absent. There is one
 * of these now, and it copies the plan straight through: an archetype decides nothing here.
 */

/** Records the page against its plan: `ctx.describePage` (compose.js) does the rest. */
export const describePage = (ctx, page) => ctx.describePage({
  archetype: page.archetype,
  variant: page.variant,
  people: page.people,
  density: page.density,
  chapters: page.chapters,
  copyKey: page.copyKey,
});
