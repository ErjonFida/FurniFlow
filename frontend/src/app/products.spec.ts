import { categoriesOf, matching, Product } from './products';

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
