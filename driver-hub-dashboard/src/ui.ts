/** Shared control styling, so the console rail and the 3D inspector stay one visual language. */

export const button: React.CSSProperties = {
  background: '#132313',
  color: '#7CFC00',
  border: '1px solid #2a4a2a',
  padding: '4px 10px',
  cursor: 'pointer',
  fontFamily: 'monospace',
};

export const chip: React.CSSProperties = {
  background: '#111a11',
  color: '#7fbf7f',
  border: '1px solid #24391f',
  padding: '1px 6px',
  fontSize: 11,
  cursor: 'pointer',
  fontFamily: 'monospace',
};

export const activeChip: React.CSSProperties = { ...chip, background: '#24391f', color: '#c8ffc8' };

export const selectStyle: React.CSSProperties = {
  background: '#132313',
  color: '#d6f5d6',
  border: '1px solid #2a4a2a',
  padding: '4px 8px',
  fontFamily: 'monospace',
};

export const railHeading: React.CSSProperties = { color: '#7CFC00', margin: '0 0 8px' };

/**
 * The alliance chips wear their alliance. The choice is not decoration — it sets the zero of every
 * heading the robot reports — and a two-state toggle that reads the same in both states is a trap.
 */
export const redChip: React.CSSProperties = {
  ...chip,
  background: '#3a1414',
  color: '#ffb3b3',
  border: '1px solid #5c2020',
};

export const blueChip: React.CSSProperties = {
  ...chip,
  background: '#14203a',
  color: '#b3ccff',
  border: '1px solid #20355c',
};

/** Names a group of controls in a bar without competing with them for attention. */
export const controlLabel: React.CSSProperties = {
  color: '#5f7a5f',
  fontSize: 11,
  letterSpacing: 1,
};

/** A select sized to sit in a bar of chips rather than beside the OpMode picker. */
export const smallSelect: React.CSSProperties = { ...selectStyle, padding: '2px 6px', fontSize: 11 };

export const numberInput: React.CSSProperties = {
  background: '#0f1a12',
  color: '#d6f5d6',
  border: '1px solid #24391f',
  padding: '2px 4px',
  width: 62,
  fontFamily: 'monospace',
  fontSize: 11,
};
