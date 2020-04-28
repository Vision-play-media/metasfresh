package de.metas.banking.payment.paymentallocation.service;

import java.util.Objects;

import javax.annotation.Nullable;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.lang.impl.TableRecordReference;

import de.metas.bpartner.BPartnerId;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Immutable allocation candidate.
 *
 * @author tsa
 *
 */
@Value
public final class AllocationLineCandidate
{
	public enum AllocationLineCandidateType
	{
		InvoiceToPayment, //
		SalesInvoiceToPurchaseInvoice, //
		InvoiceToCreditMemo, //
		InvoiceDiscountOrWriteOff, //
		InboundPaymentToOutboundPayment, //
	}

	private final AllocationLineCandidateType type;
	private final BPartnerId bpartnerId;

	private final TableRecordReference payableDocumentRef;
	private final TableRecordReference paymentDocumentRef;

	//
	// Amounts
	private final CurrencyId currencyId;
	private final AllocationAmounts amounts;
	private final Money payableOverUnderAmt;
	private final Money paymentOverUnderAmt;

	@Builder
	private AllocationLineCandidate(
			@NonNull final AllocationLineCandidateType type,
			//
			@Nullable final BPartnerId bpartnerId,
			//
			@NonNull final TableRecordReference payableDocumentRef,
			@Nullable final TableRecordReference paymentDocumentRef,
			//
			// Amounts
			@NonNull final AllocationAmounts amounts,
			@Nullable final Money payableOverUnderAmt,
			@Nullable final Money paymentOverUnderAmt)
	{
		if (Objects.equals(payableDocumentRef, paymentDocumentRef))
		{
			throw new AdempiereException("payable and payment shall not be the same but there are: " + payableDocumentRef);
		}
		if (amounts.getPayAmt().signum() != 0 && paymentDocumentRef == null)
		{
			throw new AdempiereException("paymentDocumentRef shall be not null when amount is not zero");
		}
		if (payableOverUnderAmt != null && !CurrencyId.equals(payableOverUnderAmt.getCurrencyId(), amounts.getCurrencyId()))
		{
			throw new AdempiereException("payableOverUnderAmt shall bave " + amounts.getCurrencyId() + ": " + payableOverUnderAmt);
		}
		if (paymentOverUnderAmt != null && !CurrencyId.equals(paymentOverUnderAmt.getCurrencyId(), amounts.getCurrencyId()))
		{
			throw new AdempiereException("paymentOverUnderAmt shall bave " + amounts.getCurrencyId() + ": " + paymentOverUnderAmt);
		}

		this.type = type;

		this.currencyId = amounts.getCurrencyId();
		this.amounts = amounts;
		this.payableOverUnderAmt = payableOverUnderAmt != null ? payableOverUnderAmt : Money.zero(amounts.getCurrencyId());
		this.paymentOverUnderAmt = paymentOverUnderAmt != null ? paymentOverUnderAmt : Money.zero(amounts.getCurrencyId());

		this.bpartnerId = bpartnerId;

		this.payableDocumentRef = payableDocumentRef;
		this.paymentDocumentRef = paymentDocumentRef;
	}
}
