import { parseAttributes } from './admin';

describe('parseAttributes', () => {
  it('reads one "name: value" per line and skips the rest', () => {
    expect(parseAttributes('seats: 3\nfabric:linen \n\nno colon here\nsize: 200 x 90: cm')).toEqual({
      seats: '3',
      fabric: 'linen',
      size: '200 x 90: cm',
    });
  });
});
