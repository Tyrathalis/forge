import { describe, expect, it } from 'vitest';
import { SETTINGS, stored, type SettingDef } from '../../main/ts/settings';

const def = (key: string): SettingDef => SETTINGS.find(d => d.key === key)!;
const ofType = (type: SettingDef['type']): SettingDef => SETTINGS.find(d => d.type === type)!;

describe('a stored setting', () => {
  // Fails if a playmat that was removed is still used
  it('drops a playmat that no longer exists, and keeps one that does', () => {
    expect(stored(def('playmat'), 'mistbound')).toBeUndefined();
    expect(stored(def('playmat'), 'nebula')).toBe('nebula');
  });
  // Fails if a choice value outside the current options is used
  it('drops a choice that is no longer offered', () => {
    const choice = ofType('choice') as SettingDef & { type: 'choice' };
    expect(stored(choice, 'gone')).toBeUndefined();
    expect(stored(choice, choice.options[0][0])).toBe(choice.options[0][0]);
  });
  // Fails if a value of the wrong type is used, as after a setting changes type
  it('drops a value of the wrong type', () => {
    expect(stored(ofType('toggle'), 'true')).toBeUndefined();
    expect(stored(ofType('slider'), '3')).toBeUndefined();
  });
  // Fails if a number outside a slider's range is used as it is
  it("keeps a slider's value inside its range", () => {
    const slider = ofType('slider') as SettingDef & { type: 'slider' };
    expect(stored(slider, slider.max + 100)).toBe(slider.max);
    expect(stored(slider, slider.min - 100)).toBe(slider.min);
  });
});
