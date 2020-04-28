package de.metas.banking.payment.paymentallocation.service;

import org.adempiere.util.lang.impl.TableRecordReference;

import de.metas.banking.payment.paymentallocation.service.PayableDocument.PayableDocumentType;
import de.metas.bpartner.BPartnerId;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.organization.OrgId;
import de.metas.payment.PaymentDirection;
import de.metas.util.Check;
import lombok.EqualsAndHashCode;
import lombok.NonNull;

/**
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@EqualsAndHashCode
final class PurchaseInvoiceAsPaymentDocument implements IPaymentDocument
{
	public static PurchaseInvoiceAsPaymentDocument wrap(final PayableDocument creditMemoPayableDoc)
	{
		return new PurchaseInvoiceAsPaymentDocument(creditMemoPayableDoc);
	}

	private final PayableDocument purchaseInvoicePayableDoc;

	private PurchaseInvoiceAsPaymentDocument(@NonNull final PayableDocument purchasePayableDoc)
	{
		Check.assume(!purchasePayableDoc.isCreditMemo(), "is not credit memo: {}", purchasePayableDoc);
		Check.assume(purchasePayableDoc.getSoTrx().isPurchase(), "is purchase document: {}", purchasePayableDoc);
		this.purchaseInvoicePayableDoc = purchasePayableDoc;
	}

	@Override
	public String toString()
	{
		return getClass().getSimpleName() + "[" + purchaseInvoicePayableDoc.toString() + "]";
	}

	@Override
	public PaymentDocumentType getType()
	{
		return PaymentDocumentType.PurchaseInvoice;
	}

	@Override
	public OrgId getOrgId()
	{
		return purchaseInvoicePayableDoc.getOrgId();
	}

	@Override
	public BPartnerId getBpartnerId()
	{
		return purchaseInvoicePayableDoc.getBpartnerId();
	}

	@Override
	public String getDocumentNo()
	{
		return purchaseInvoicePayableDoc.getDocumentNo();
	}

	@Override
	public TableRecordReference getReference()
	{
		return purchaseInvoicePayableDoc.getReference();
	}

	@Override
	public Money getAmountToAllocateInitial()
	{
		return purchaseInvoicePayableDoc.getAmountsToAllocateInitial().getPayAmt().negate();
	}

	@Override
	public Money getAmountToAllocate()
	{
		return purchaseInvoicePayableDoc.getAmountsToAllocate().getPayAmt().negate();
	}

	@Override
	public void addAllocatedAmt(Money allocatedAmtToAdd)
	{
		purchaseInvoicePayableDoc.addAllocatedAmounts(AllocationAmounts.ofPayAmt(allocatedAmtToAdd.negate()));
	}

	@Override
	public boolean isFullyAllocated()
	{
		return purchaseInvoicePayableDoc.isFullyAllocated();
	}

	@Override
	public Money calculateProjectedOverUnderAmt(Money amountToAllocate)
	{
		return purchaseInvoicePayableDoc.computeProjectedOverUnderAmt(AllocationAmounts.ofPayAmt(amountToAllocate.negate()));
	}

	@Override
	public boolean canPay(@NonNull final PayableDocument payable)
	{
		if (payable.getType() != PayableDocumentType.Invoice)
		{
			return false;
		}
		if (payable.getSoTrx() != purchaseInvoicePayableDoc.getSoTrx())
		{
			return false;
		}

		// A purchase invoice cannot pay another purchase invoice
		if (payable.getSoTrx().isPurchase())
		{
			return false;
		}

		// if currency differs, do not allow payment
		if (!CurrencyId.equals(payable.getCurrencyId(), purchaseInvoicePayableDoc.getCurrencyId()))
		{
			return false;
		}

		return true;
	}

	@Override
	public PaymentDirection getPaymentDirection()
	{
		return PaymentDirection.ofSOTrx(purchaseInvoicePayableDoc.getSoTrx());
	}

	@Override
	public CurrencyId getCurrencyId()
	{
		return purchaseInvoicePayableDoc.getCurrencyId();
	}
}
