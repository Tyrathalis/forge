// The hooks that connect the campaign modes to the page. campaign/register.tsx sets them, so a build can leave the campaigns out

import type { FunctionComponent, VNode } from 'preact';
import type { Actions, PageActions } from './actions';
import type { Model } from './model';
import type { DraftState, Hello, Send, ServerMessage } from './protocol';
import type { TextKey } from './text';

/** How a campaign's game ends: what the result says and the buttons it offers. */
export interface CampaignEnding {
  /** Identifies the result, so a panel drawn before the result arrived is drawn again. Null until the result arrives. */
  key: string | null;
  line: string | null;
  buttons(panel: EndingPanel): void;
}

/** The bottom of the result panel, where an ending adds its buttons. */
export interface EndingPanel {
  add(label: string, primary: boolean, onClick: () => void): void;
  /** A line above the buttons, such as a question they answer. */
  say(text: string): void;
  /** Empties the bottom of the panel so another set of buttons can be added. */
  clear(): void;
  /** Shows the battlefield in place of the result. */
  view(): void;
}

/** A draft played for a campaign. It has its own end screen and its own text for what leaving it costs. */
export interface CampaignDraft {
  Done: FunctionComponent<{ state: DraftState; actions: Actions }>;
  leaveText: TextKey;
}

export interface CampaignHooks {
  /** The fields the campaigns add to the page model, with their starting values. */
  fields(): Partial<Model>;
  /** Runs on every hello. A campaign clears the state of any page that is no longer shown. */
  hello(model: Model, msg: Hello): void;
  /** Stores a campaign message in the model. Returns false for any other message. */
  message(model: Model, msg: ServerMessage): boolean;
  actions(send: Send, model: Model, schedule: () => void): Omit<Actions, keyof PageActions>;
  /** The pages of the campaign mode that is open. */
  Page: FunctionComponent<{ model: Model; actions: Actions }>;
  /** The start page's entries for the campaigns. */
  Menu: FunctionComponent<{ model: Model; actions: Actions }>;
  /** The draft running for a campaign, or null when the draft belongs to the Limited pages. */
  draft(model: Model): CampaignDraft | null;
  /** The campaign ending for the game that just ended, or null when the match is not part of a campaign. */
  ending(model: Model, actions: Actions): CampaignEnding | null;
}

let hooks: CampaignHooks | null = null;

export function registerCampaigns(h: CampaignHooks): void {
  hooks = h;
}

export function campaigns(): CampaignHooks | null {
  return hooks;
}

/** The start page's campaign entries, or nothing in a build without campaigns. */
export function CampaignMenu(props: { model: Model; actions: Actions }): VNode | null {
  const Menu = hooks?.Menu;
  return Menu ? <Menu {...props} /> : null;
}
