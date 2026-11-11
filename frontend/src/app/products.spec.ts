import { categoriesOf, matching, Product, srcsetOf } from './products';

describe('the shop filters', () => {
  const product = (name: string, category: string) => ({ sku: name, name, category, price: 1, attributes: {} }) as Product;
  const sofa = product('Oslo sofa', 'Sofas');
  const armchair = product('Oslo armchair', 'Armchairs');
  const table = product('Oak table', 'Tables');
  const shop = [sofa, armchair, table];

  it('shows everything until a category or search is chosen', () => {
    expect(matching(shop)).toEqual(shop);
  });

  it('narrows by category, by name in any case, and by both', () => {
    expect(matching(shop, 'Sofas')).toEqual([sofa]);
    expect(matching(shop, undefined, ' oslo ')).toEqual([sofa, armchair]);
    expect(matching(shop, 'Armchairs', 'OSLO')).toEqual([armchair]);
    expect(matching(shop, 'Tables', 'oslo')).toEqual([]);
  });

  it('lists each category once, in order', () => {
    expect(categoriesOf([table, sofa, armchair, sofa])).toEqual(['Armchairs', 'Sofas', 'Tables']);
  });
});

describe('photo sizes', () => {
  it('offers smaller copies of an Unsplash photo, keeping its other settings', () => {
    const copies = srcsetOf('https://images.unsplash.com/photo-1?w=1000&q=75&auto=format')!.split(', ');
    expect(copies[0]).toBe('https://images.unsplash.com/photo-1?w=120&q=75&auto=format 120w');
    expect(copies.at(-1)).toBe('https://images.unsplash.com/photo-1?w=1000&q=75&auto=format 1000w');
  });

  it("leaves other addresses alone, since they can't be asked for a size", () => {
    expect(srcsetOf('https://example.com/sofa.jpg?w=1000')).toBeNull();
  });
});
