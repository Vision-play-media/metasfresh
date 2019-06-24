import { getLanguageSpecific } from './utils';

export class Product {
  constructor(name) {
    cy.log(`Product - set name = ${name}`);
    this.name = name;
    this.attributeValues = [];
    this.productPrices = [];
  }

  setName(name) {
    cy.log(`Product - set name = ${name}`);
    this.name = name;
    return this;
  }

  setValue(value) {
    cy.log(`Product - set value = ${value}`);
    this.value = value;
    return this;
  }

  setDescription(description) {
    cy.log(`Product - set description = ${description}`);
    this.description = description;
    return this;
  }

  setStocked(isStocked) {
    cy.log(`Product - set isStocked = ${isStocked}`);
    this.isStocked = isStocked;
    return this;
  }

  setPurchased(isPurchased) {
    cy.log(`Product - set isPurchased = ${isPurchased}`);
    this.isPurchased = isPurchased;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setSold(isSold) {
    cy.log(`Product - set isSold = ${isSold}`);
    this.isSold = isSold;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setDiverse(isDiverse) {
    cy.log(`Product - set isDiverse = ${isDiverse}`);
    this.isDiverse = isDiverse;
    return this;
  }

  setProductCategory(m_product_category) {
    cy.log(`Product - set productCategory = ${m_product_category}`);
    this.m_product_category = m_product_category;
    return this;
  }

  setProductType(productType) {
    cy.log(`Product - set productType = ${productType}`);
    this.productType = productType;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setUOM(c_uom) {
    cy.log(`Product - set c_uom = ${c_uom}`);
    this.c_uom = c_uom;
    return this;
  }

  addProductPrice(productPrice) {
    cy.log(`Product - productPrice = ${JSON.stringify(productPrice)}`);
    this.productPrices.push(productPrice);
    return this;
  }


  apply() {
    cy.log(`Product - apply - START (name=${this.name})`);
    Product.applyProduct(this);
    cy.log(`Product - apply - END (name=${this.name})`);
    return this;
  }

  static applyProduct(product) {
    describe(`Create new Product ${product.name}`, function () {
      cy.visitWindow('140', 'NEW');
      cy.writeIntoStringField('Name', product.name);

      // Value is readonly
      //cy.clearField('Value');
      //cy.writeIntoStringField('Value', product.value);

      cy.selectInListField('M_Product_Category_ID', product.m_product_category);

      cy.writeIntoStringField('Description', product.description);

    cy.getFieldValue('IsStocked').then(isIsStockedValue => {
        if (product.isStocked && !isIsStockedValue) {
          cy.clickOnCheckBox('IsStocked');
        }
      });
    cy.getFieldValue('IsPurchased').then(isPurchasedValue => {
        if (product.isPurchased && !isPurchasedValue) {
          cy.clickOnCheckBox('IsPurchased');
        }
      });
    cy.getFieldValue('IsSold').then(isSoldValue => {
        if (product.isSold && !isSoldValue) {
          cy.clickOnCheckBox('IsSold');
        }
      });
    cy.getFieldValue('IsDiverse').then(isDiverseValue => {
        if (product.isDiverse && !isDiverseValue) {
          cy.clickOnCheckBox('IsDiverse');
        }
      });

      cy.getFieldValue('ProductType').then(productTypeValue => {
        const productType = getLanguageSpecific(product, 'productType');

        if (productType != productTypeValue) {
          cy.selectInListField('ProductType', productType);
        }
      });

      cy.getFieldValue('C_UOM_ID').then(uomValue => {
        const c_uom = getLanguageSpecific(product, 'c_uom');

        if (c_uom && c_uom != uomValue) {
          cy.selectInListField('C_UOM_ID', c_uom);
        }
      });

      if (product.productPrices.length > 0) {
        product.productPrices.forEach(function (price) {
          Product.applyProductPrice(price);
        });
        cy.get('table tbody tr').should('have.length', product.productPrices.length);
      }
    });
  }

  /**
   * See complaint at ProductPrice class.
   */
  static applyProductPrice(productPrice) {
    // const m_pricelist_version = getLanguageSpecific(productPrice, 'm_pricelist_version');


    cy.selectTab('M_ProductPrice');
    cy.pressAddNewButton();
    cy.writeIntoLookupListField('M_PriceList_Version_ID', productPrice.priceList, productPrice.priceList, false, true);

    cy.writeIntoStringField('PriceList', productPrice.listPriceAmount, true, null, true);
    cy.writeIntoStringField('PriceStd', productPrice.standardPriceAmount, true, null, true);
    cy.writeIntoStringField('PriceLimit', productPrice.limitPriceAmount, true, null, true);

    // this (getLanguageSpecific) fails for tbp with "CypressError: cy.contains() can only accept a string, number or regular expression."
    // cy.selectInListField('C_TaxCategory_ID', getLanguageSpecific(productPrice.taxCategory, 'c_taxcategory'));

    cy.selectInListField('C_TaxCategory_ID', productPrice.taxCategory);
    cy.pressDoneButton();
  }
}


export class ProductCategory {
  constructor(name) {
    cy.log(`Product Category - set name = ${name}`);
    this.name = name;
  }

  setName(name) {
    cy.log(`Product Category - set name = ${name}`);
    this.name = name;
    return this;
  }

  setValue(value) {
    cy.log(`Product Category - set value = ${value}`);
    this.value = value;
    return this;
  }

  apply() {
    cy.log(`Product Category - apply - START (name=${this.name})`);
    ProductCategory.applyProductCategory(this);
    cy.log(`Product Category - apply - END (name=${this.name})`);
    return this;
  }


  static applyProductCategory(productCategory) {
    describe(`Create new Product ${productCategory.name}`, function () {
      cy.visitWindow('144', 'NEW');
      cy.writeIntoStringField('Name', productCategory.name);

      // Value is updateable
      cy.writeIntoStringField('Value', productCategory.value);
    });
  }
}


/**
 * i dont like this, but it seems when i search for a price list version, the search does NOT use PriceListVersion.Name,
 * but instead a combination of PriceList.Name and PriceListVersion.FromDate.
 * Why? IDK!
 *
 * FIXPLS
 */
export class ProductPrice {
  setPriceList(priceList) {
    cy.log(`ProductPrice priceList - set = ${priceList}`);
    this.priceList = priceList;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setListPriceAmount(listPriceAmount) {
    cy.log(`ProductPrice listPriceAmount - set = ${listPriceAmount}`);
    this.listPriceAmount = listPriceAmount;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setStandardPriceAmount(standardPriceAmount) {
    cy.log(`ProductPrice standardPriceAmount - set = ${standardPriceAmount}`);
    this.standardPriceAmount = standardPriceAmount;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setLimitPriceAmount(limitPriceAmount) {
    cy.log(`ProductPrice limitPriceAmount - set = ${limitPriceAmount}`);
    this.limitPriceAmount = limitPriceAmount;
    return this;
  }

  // noinspection JSUnusedGlobalSymbols
  setTaxCategory(taxCategory) {
    cy.log(`ProductPrice taxCategory - set = ${taxCategory}`);
    this.taxCategory = taxCategory;
    return this;
  }
}
