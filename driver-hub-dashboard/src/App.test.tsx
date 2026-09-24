// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { OpModeControls, ScenarioPicker, SimScoreReadout } from './App';
import type { OpModeState, SimScenarios, SimScore } from './protocol';
import { blueChip, redChip } from './ui';

/**
 * The CELL score in the sim strip, rendered, for the two things about it that are not formatting.
 *
 * <p>A null score must render nothing: it is the difference between "the CELLs are empty" and "the
 * server has not said", and the second is what a fresh page, a robot with no HIVE, and a dead
 * socket all have. A guard written as {@code score.redPoints ?? 0}, or a call site that forgot the
 * conditional, produces a confident 0 – 0 that looks exactly like a real score.</p>
 *
 * <p>And each total must be in its own alliance's colour, because that is the only thing on screen
 * saying which number belongs to whom at a glance: swapping the two spans is a one-character bug
 * that no amount of squinting at the numbers reveals.</p>
 */
declare global {
  /** React reads this to decide whether {@code act} is allowed; it is not part of lib.dom. */
  var IS_REACT_ACT_ENVIRONMENT: boolean;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const SCORE: SimScore = {
  redPoints: 6,
  bluePoints: 0,
  cells: [
    { cell: 'RED AUDIENCE', alliance: 'red', holding: 3, points: 6 },
    { cell: 'BLUE SCORING', alliance: 'blue', holding: 0, points: 0 },
  ],
};

const host = document.createElement('div');
document.body.append(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(null));
});

function show(score: SimScore | null): void {
  act(() => root.render(<SimScoreReadout score={score} />));
}

describe('the CELL score readout', () => {
  it('renders nothing at all before anything has said what the CELLs hold', () => {
    show(null);
    expect(host.textContent).toBe('');
  });

  it('shows each alliance its own total, in its own colour', () => {
    show(SCORE);
    const coloured = (colour: unknown) =>
      [...host.querySelectorAll('span')].filter((span) => span.style.color === colour);
    // Colours compared against the chips themselves: a palette edit in ui.ts should move this
    // readout with it rather than fail here.
    expect(coloured(redChip.color).map((span) => span.textContent)).toEqual(['RED 6']);
    expect(coloured(blueChip.color).map((span) => span.textContent)).toEqual(['BLUE 0']);
  });

  it('names every upward-facing CELL and what it holds in the tooltip', () => {
    // The breakdown is the whole reason the bar itself can stay two numbers wide. Losing it costs
    // nothing visible, so nothing but this notices.
    show(SCORE);
    const title = host.querySelector('span')?.getAttribute('title') ?? '';
    expect(title).toContain('RED AUDIENCE: 3 holding, 6 points');
    expect(title).toContain('BLUE SCORING: 0 holding, 0 points');
  });
});

/**
 * The scenario picker, rendered, for the three things about it that are not formatting: that it
 * stays away when there is nothing to pick from, that it shows the field the server says is
 * staged, and that choosing an entry names the right scenario — including the entry that is not a
 * scenario at all.
 */
const SCENARIOS: SimScenarios = {
  // "-1" is a scenario name here on purpose: it is the string the own-field entry uses as its
  // option value, so a picker that matched entries by name rather than by position would send this
  // file's name when someone asked for the robot's own field, and vice versa.
  scenarios: ['-1', 'hives-tipped-back', 'match-staging'],
  directory: '/Users/team/robot/TeamCode/scenarios',
  active: 'match-staging',
};

function picker(scenarios: SimScenarios | null, onChoose: (name: string | null) => void = () => {}) {
  act(() => root.render(<ScenarioPicker scenarios={scenarios} onChoose={onChoose} />));
  return host.querySelector('select');
}

/**
 * Picks an entry the way a person does. Through the prototype's own setter, as
 * {@code Inspector.test} drags a slider: react-dom tracks the current value on the element itself.
 */
function choose(select: HTMLSelectElement, label: string): void {
  const option = [...select.options].find((candidate) => candidate.textContent === label);
  expect(option, `no entry labelled ${label}`).toBeDefined();
  const native = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set;
  act(() => {
    native?.call(select, option?.value);
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

describe('the scenario picker', () => {
  it('renders nothing at all before the server has listed any scenarios', () => {
    // A robot that simulates no field never sends the frame, and neither does a dead socket. An
    // empty select there is a control that cannot do anything, which reads as a broken one.
    expect(picker(null)).toBeNull();
    expect(host.textContent).toBe('');
  });

  it('offers the robot own field alongside every scenario, with the staged one selected', () => {
    const select = picker(SCENARIOS);
    expect([...(select?.options ?? [])].map((option) => option.textContent)).toEqual([
      "the robot's own field",
      ...SCENARIOS.scenarios,
    ]);
    // Read off the server's frame rather than from a click: a picker showing the first entry while
    // another scenario is staged is a confident lie about what is on the field.
    expect([...(select?.selectedOptions ?? [])].map((option) => option.textContent)).toEqual([
      'match-staging',
    ]);
  });

  it('names the scenario that was chosen, even one spelled like the own-field entry', () => {
    const chosen: (string | null)[] = [];
    const select = picker(SCENARIOS, (name) => chosen.push(name));
    choose(select as HTMLSelectElement, 'hives-tipped-back');
    choose(select as HTMLSelectElement, '-1');
    expect(chosen).toEqual(['hives-tipped-back', '-1']);
  });

  it('asks for null when the robot own field is chosen, not for a scenario of that name', () => {
    // Null is the one value that means "unstage everything"; sending a name here would look for a
    // file, and on a disk holding the file this list names, it would find one.
    const chosen: (string | null)[] = [];
    const select = picker(SCENARIOS, (name) => chosen.push(name));
    choose(select as HTMLSelectElement, "the robot's own field");
    expect(chosen).toEqual([null]);
  });

  it('still shows a staged scenario the server has stopped listing', () => {
    // Rename or delete a scenario file under a running session and the server goes on reporting
    // it as loaded, because it is: the balls are on the field. The list it comes from is read off
    // the directory afresh, so the name is gone from it. Matching by position alone answered -1
    // here, which is the own-field entry's value, so the picker claimed nothing was staged while a
    // full field sat in front of the camera.
    const renamed: SimScenarios = { ...SCENARIOS, active: 'staged-then-renamed' };
    const select = picker(renamed);

    expect([...(select?.selectedOptions ?? [])].map((option) => option.textContent)).toEqual([
      'staged-then-renamed',
    ]);
    expect([...(select?.options ?? [])].map((option) => option.textContent)).toEqual([
      "the robot's own field",
      'staged-then-renamed',
      ...SCENARIOS.scenarios,
    ]);
  });

  it('says where the scenario files live, so a new one can be added', () => {
    // The only place the directory appears. Without it the picker lists names from a folder the
    // person reading it has no way to find.
    expect(picker(SCENARIOS)?.getAttribute('title')).toContain(SCENARIOS.directory);
  });
});

/**
 * The OpMode buttons, for the one thing about them that is not formatting: which actions the strip
 * offers in each state. INIT and START are mutually exclusive and STOP means nothing with no
 * OpMode, so the strip is one button before INIT and two after — a set that is wrong in either
 * direction is a control that either lies about what can be done or hides the only thing that can.
 */
function controls(state: OpModeState, onAct: (what: string) => void = () => {}) {
  act(() =>
    root.render(
      <OpModeControls
        state={state}
        selected="org.team.Auto"
        onInit={() => onAct('init')}
        onStart={() => onAct('start')}
        onStop={() => onAct('stop')}
      />,
    ),
  );
  return [...host.querySelectorAll('button')];
}

describe('the OpMode controls', () => {
  it('offers only init before anything is initialised', () => {
    expect(controls('STOPPED').map((element) => element.textContent)).toEqual(['init']);
  });

  it('offers start and stop once an OpMode is initialised, and no second init', () => {
    expect(controls('INIT').map((element) => element.textContent)).toEqual(['start', 'stop']);
  });

  it('leaves only stop live while the OpMode runs', () => {
    // The slot keeps its spent start rather than collapsing: a driver's hand is on that button as
    // the match begins, and stop sliding under it at that instant is the one misclick that costs a
    // run.
    expect(controls('RUNNING').map((element) => element.textContent)).toEqual(['start', 'stop']);
    expect(
      controls('RUNNING')
        .filter((live) => !live.disabled)
        .map((live) => live.textContent),
    ).toEqual(['stop']);
  });

  it('sends the action its button names', () => {
    const acted: string[] = [];
    const [init] = controls('STOPPED', (what) => acted.push(what));
    act(() => init.click());
    const [start, stop] = controls('INIT', (what) => acted.push(what));
    act(() => start.click());
    act(() => stop.click());
    expect(acted).toEqual(['init', 'start', 'stop']);
  });

  it('will not init with no OpMode chosen', () => {
    // The select is empty until the server lists something; init on nothing is a message the
    // server answers with an error.
    act(() =>
      root.render(
        <OpModeControls
          state="STOPPED"
          selected=""
          onInit={() => {}}
          onStart={() => {}}
          onStop={() => {}}
        />,
      ),
    );
    expect(host.querySelector('button')?.disabled).toBe(true);
  });
});
