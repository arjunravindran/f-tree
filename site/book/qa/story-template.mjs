/*
 * The storybook template every wave-3 test composes against.
 *
 * The real template now ships as `templates/diwali-story.json`: a file in `templates/` must be in
 * the catalogue (`catalog.test.mjs`), and a catalogue entry at format 2 must be drawable
 * (`invariants.test.mjs`), so the template arrived with the last archetype (#258), not before it.
 * It is listed at format 2 while the app reads format 1 (`template.js`'s `TEMPLATE_FORMAT`), which
 * is what keeps it hidden from the picker without a flag of its own - `catalog.js`'s `available`
 * drops an entry newer than this app draws. #259 is the swap that shows it.
 *
 * This file stays the authored copy, because JSON cannot carry the reasons: every line below was
 * argued over in a design-critic round, and the notes are the record of what was tried and why it
 * changed. `invariants.test.mjs` holds the shipped JSON to this document exactly, so the two are
 * one source and cannot drift; edit here, then regenerate the file.
 *
 * The palette is the one the approved style frames were rendered from
 * (`art/style-frames/motifs.mjs`), which is what `docs/book-design-system.md` documents and what
 * `art/src/papercut/swatches.json` maps authoring colours onto. The copy is the story sequence in
 * `docs/storybook-plan.md`. #259 owns the final wording when Diwali swaps over.
 *
 * Test-only: nothing the composer imports may reach this file.
 */

/** The 29 paper-cut palette tokens, at the approved frames' own colours. */
export const PAPERCUT_PALETTE = Object.freeze({
  paper: '#f6ecda', paperDeep: '#ead7b5', card: '#fff8ec',
  ink: '#2a1a33', inkSoft: '#5e4a66',
  night: '#1f1840', deep: '#17122e', glow: '#3a2352', dusk: '#7a3e63', haze: '#5a3462',
  gold: '#f2b84b', flame: '#ffe7a6', brass: '#b9822a',
  marigold: '#f2a71b', saffron: '#e8762b', sindoor: '#c23b2e', rani: '#d6336c',
  peacock: '#0f7b7a', indigo: '#3b4a8c', leaf: '#5a8a3c', leafDeep: '#2f5a2a',
  stone: '#d9a77a', clay: '#b5562a', skin: '#b97a52', silver: '#d9d2ca',
  sky: '#f3ddb8', wash: '#6f93c7', dayHaze: '#e9b777', dayMid: '#d99a62',
});

/**
 * The storybook template as a template document (not yet validated), every chapter the planner
 * knows, in the story's order.
 *
 * `{featured}`, `{featured-first}`, `{family}`, `{from-family}`, `{n}` and `{year}` are the only
 * placeholders a template may use, and a line that has no fact to fill one drops the clause rather than printing
 * a gap (`copy.js`'s `renderCopy`). A `line` written as `{ one, other }` picks by `{n}`.
 */
export const STORY_TEMPLATE = Object.freeze({
  format: 2,
  id: 'diwali-story',
  name: 'Diwali, the storybook',
  fileSuffix: 'Diwali Book',
  art: 'papercut',
  fonts: { display: 'book_display', text: 'book_text', strong: 'book_strong', hand: 'book_hand' },
  palette: PAPERCUT_PALETTE,
  cover: {
    greeting: 'शुभ दीपावली',
    subtitle: '{from-family}',
    // Round 2, finding 20: the count is the cover's entire emotional payload, and "One lamp for
    // each of us" said it as a slogan rather than a number. `{Count-words}` is the same form
    // `templates/diwali.json`'s format-1 cover already carries.
    line: { one: 'One lamp, and the family behind it', other: '{Count-words} lamps, one for each of us.' },
  },
  story: {
    chapters: [
      'cover', 'opening', 'roots', 'courtyards', 'parents', 'siblings', 'spouses', 'children',
      'lane', 'numbers', 'register', 'still-to-be-found', 'legacy', 'closing',
    ],
  },
  copy: {
    // Round 2, finding 21: 'This is {featured}' as a title, over a body that itself ends "This is
    // the family behind {featured}", doubled the name three times on one page. The approved
    // opening frame's own title is "This is {featured}'s story"; the line is `openingHero`'s own
    // fallback for the rare case `copy.js`'s `openingLine` has nothing composed to say.
    opening: { title: "This is {featured}'s story", line: 'Everything this family remembers starts here.' },
    roots: { title: 'Where it begins', line: { one: 'The earliest name this family remembers.', other: 'The earliest names this family remembers.' } },
    courtyards: { title: 'Two courtyards', line: 'The houses {featured-first} comes from.' },
    // Round 2, finding 23: Devanagari kin words joined by an English "and" in the display face
    // read as neither language; the captions under the two portraits already say "mother" and
    // "father" in English (`kinCaption`), and the kin words themselves belong in `hand` under a
    // name, not in a chapter's own title.
    parents: { title: 'Mother and father', line: 'The two who began this house, and the family they grew up in.' },
    siblings: { title: 'Growing up together', line: { one: 'The one who shared the house with {featured-first}.', other: 'The ones who shared the house with {featured-first}.' } },
    spouses: { title: 'A new family joins', line: 'A second family, joined to this one.' },
    children: { title: 'The next lamps', line: { one: 'The lamp lit after {featured-first}.', other: 'The lamps lit after {featured-first}.' } },
    lane: { title: 'Our lane', line: 'A house for every branch of the family.' },
    numbers: { title: 'In numbers', line: 'This family, counted from where {featured-first} stands.' },
    register: { title: 'Everyone in our family', line: { one: '{Count-words} person, and where to find them.', other: 'All {count-words} of us, and where to find each one.' } },
    'still-to-be-found': { title: 'Still to be found', line: { one: 'One lamp is lit for a name nobody has written down yet.', other: '{Count-words} lamps are lit for names nobody has written down yet.' } },
    // Ankit's decision 8: the page draws a lamp for every generation behind F *and* a larger one
    // for F, with their name under it - so a line that counted only the generations behind left
    // the reader one lamp they could not account for. The line was the wrong half to fix.
    legacy: {
      title: 'One line of light',
      line: {
        one: 'One lamp, and it is {featured-first}\u2019s own.',
        other: 'One lamp for {featured-first}, and one for every generation behind.',
      },
    },
    // Round 2, finding 19: the closing used to repeat the cover's own greeting and its
    // "from the X family" line word for word, with no `hand` face anywhere on the page. Its own
    // farewell, distinct from the cover's arrival greeting, closes the book instead.
    closing: { title: 'Until next Diwali', line: 'Add them, with a name, a year or a photograph, and next year\u2019s book will have them.' },
  },
});
