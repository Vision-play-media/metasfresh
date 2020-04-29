package de.metas.banking.payment.paymentallocation.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.model.I_C_AllocationHdr;
import org.compiere.model.I_C_AllocationLine;
import org.compiere.model.I_C_Invoice;
import org.compiere.model.I_C_Payment;
import org.compiere.util.TimeUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import de.metas.allocation.api.C_AllocationHdr_Builder;
import de.metas.allocation.api.C_AllocationLine_Builder;
import de.metas.allocation.api.IAllocationBL;
import de.metas.allocation.api.PaymentAllocationId;
import de.metas.banking.payment.paymentallocation.service.AllocationLineCandidate.AllocationLineCandidateType;
import de.metas.bpartner.BPartnerId;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.organization.OrgId;
import de.metas.util.Check;
import de.metas.util.OptionalDeferredException;
import de.metas.util.Services;
import lombok.NonNull;

/**
 * Builds one {@link I_C_AllocationHdr} of all given {@link PayableDocument}s and {@link IPaymentDocument}s.
 *
 * @author tsa
 *
 */
public class PaymentAllocationBuilder
{
	public enum PayableRemainingOpenAmtPolicy
	{
		DO_NOTHING, WRITE_OFF, DISCOUNT
	}

	public static final PaymentAllocationBuilder newBuilder()
	{
		return new PaymentAllocationBuilder();
	}

	// services
	private final ITrxManager trxManager = Services.get(ITrxManager.class);
	private final IAllocationBL allocationBL = Services.get(IAllocationBL.class);

	// Parameters
	private LocalDate _dateTrx;
	private LocalDate _dateAcct;
	private ImmutableList<PayableDocument> _payableDocuments = ImmutableList.of();
	private ImmutableList<IPaymentDocument> _paymentDocuments = ImmutableList.of();
	private boolean allowOnlyOneVendorDoc = true;
	private boolean allowPartialAllocations = false;
	private boolean allowPurchaseSalesInvoiceCompensation;
	private PayableRemainingOpenAmtPolicy payableRemainingOpenAmtPolicy = PayableRemainingOpenAmtPolicy.DO_NOTHING;
	private boolean dryRun = false;

	// Status
	private boolean _built = false;

	private PaymentAllocationBuilder()
	{
	}

	/**
	 * @return result message (to be displayed in UI status bar)
	 * @throws PaymentAllocationException in case of any error
	 */
	public PaymentAllocationResult build()
	{
		markAsBuilt();

		//
		// Create allocation candidates
		final ImmutableList<AllocationLineCandidate> candidates = createAllocationLineCandidates();

		final OptionalDeferredException<PaymentAllocationException> fullyAllocatedCheck;
		if (!allowPartialAllocations)
		{
			fullyAllocatedCheck = checkFullyAllocated();
			if (!dryRun)
			{
				fullyAllocatedCheck.throwIfError();
			}
		}
		else
		{
			fullyAllocatedCheck = OptionalDeferredException.noError();
		}

		//
		// Create & process allocation documents
		final ImmutableSet<PaymentAllocationId> paymentAllocationIds;
		if (!candidates.isEmpty() && !dryRun)
		{
			try
			{
				paymentAllocationIds = trxManager.callInNewTrx(() -> createAndCompleteAllocations(candidates));
			}
			catch (final Exception ex)
			{
				throw PaymentAllocationException.wrapIfNeeded(ex);
			}
		}
		else
		{
			paymentAllocationIds = ImmutableSet.of();
		}

		return PaymentAllocationResult.builder()
				.candidates(candidates)
				.fullyAllocatedCheck(fullyAllocatedCheck)
				.paymentAllocationIds(paymentAllocationIds)
				.build();
	}

	private final ImmutableSet<PaymentAllocationId> createAndCompleteAllocations(final List<AllocationLineCandidate> lines)
	{
		final ImmutableSet.Builder<PaymentAllocationId> paymentAllocationIds = ImmutableSet.builder();

		for (final AllocationLineCandidate line : lines)
		{
			final PaymentAllocationId paymentAllocationId = createAndCompleteAllocation(line);
			if (paymentAllocationId != null)
			{
				paymentAllocationIds.add(paymentAllocationId);
			}
		}

		return paymentAllocationIds.build();
	}

	/**
	 * Create and complete one {@link I_C_AllocationHdr} for given candidate.
	 */
	private final PaymentAllocationId createAndCompleteAllocation(final AllocationLineCandidate line)
	{
		final OrgId adOrgId = line.getOrgId();
		final CurrencyId currencyId = line.getCurrencyId();
		final Timestamp dateTrx = TimeUtil.asTimestamp(getDateTrx());
		final Timestamp dateAcct = TimeUtil.asTimestamp(getDateAcct());

		final C_AllocationHdr_Builder allocationBuilder = allocationBL.newBuilder()
				.orgId(adOrgId.getRepoId())
				.currencyId(currencyId.getRepoId())
				.dateAcct(dateAcct)
				.dateTrx(dateTrx)
				.manual(true); // flag it as manually created by user

		final C_AllocationLine_Builder payableLineBuilder = allocationBuilder.addLine()
				.orgId(adOrgId.getRepoId())
				.bpartnerId(BPartnerId.toRepoId(line.getBpartnerId()))
				//
				// Amounts
				.amount(line.getAmounts().getPayAmt().toBigDecimal())
				.discountAmt(line.getAmounts().getDiscountAmt().toBigDecimal())
				.writeOffAmt(line.getAmounts().getWriteOffAmt().toBigDecimal())
				.overUnderAmt(line.getPayableOverUnderAmt().toBigDecimal())
				.skipIfAllAmountsAreZero();

		final TableRecordReference payableDocRef = line.getPayableDocumentRef();
		final String payableDocTableName = payableDocRef.getTableName();
		final TableRecordReference paymentDocRef = line.getPaymentDocumentRef();
		final String paymentDocTableName = paymentDocRef == null ? null : paymentDocRef.getTableName();

		//
		// Invoice - Payment
		if (I_C_Invoice.Table_Name.equals(payableDocTableName)
				&& I_C_Payment.Table_Name.equals(paymentDocTableName))
		{
			payableLineBuilder.invoiceId(payableDocRef.getRecord_ID());
			payableLineBuilder.paymentId(paymentDocRef.getRecord_ID());
		}
		//
		// Invoice - CreditMemo invoice
		// or Sales invoice - Purchase Invoice
		else if (I_C_Invoice.Table_Name.equals(payableDocTableName)
				&& I_C_Invoice.Table_Name.equals(paymentDocTableName))
		{
			payableLineBuilder.invoiceId(payableDocRef.getRecord_ID());
			//

			// Credit memo line
			allocationBuilder.addLine()
					.orgId(adOrgId.getRepoId())
					.bpartnerId(BPartnerId.toRepoId(line.getBpartnerId()))
					//
					// Amounts
					.amount(line.getAmounts().getPayAmt().negate().toBigDecimal())
					.overUnderAmt(line.getPaymentOverUnderAmt().toBigDecimal())
					.skipIfAllAmountsAreZero()
					//
					.invoiceId(paymentDocRef.getRecord_ID());
		}
		//
		// Invoice - just Discount/WriteOff
		else if (I_C_Invoice.Table_Name.equals(payableDocTableName)
				&& paymentDocTableName == null)
		{
			payableLineBuilder.invoiceId(payableDocRef.getRecord_ID());
			// allow only if the line's amount is zero, because else, we need to have a document where to allocate.
			Check.assume(line.getAmounts().getPayAmt().signum() == 0, "zero amount: {}", line);
		}
		//
		// Outgoing payment - Incoming payment
		else if (I_C_Payment.Table_Name.equals(payableDocTableName)
				&& I_C_Payment.Table_Name.equals(paymentDocTableName))
		{
			payableLineBuilder.paymentId(payableDocRef.getRecord_ID());
			// Incoming payment line
			allocationBuilder.addLine()
					.orgId(adOrgId.getRepoId())
					.bpartnerId(BPartnerId.toRepoId(line.getBpartnerId()))
					//
					// Amounts
					.amount(line.getAmounts().getPayAmt().negate().toBigDecimal())
					.overUnderAmt(line.getPaymentOverUnderAmt().toBigDecimal())
					.skipIfAllAmountsAreZero()
					//
					.paymentId(paymentDocRef.getRecord_ID());
		}
		else
		{
			throw new InvalidDocumentsPaymentAllocationException(payableDocRef, paymentDocRef);
		}

		final I_C_AllocationHdr allocationHdr = allocationBuilder.createAndComplete();
		if (allocationHdr == null)
		{
			return null;
		}

		//
		final ImmutableList<I_C_AllocationLine> lines = allocationBuilder.getC_AllocationLines();
		updateCounter_AllocationLine_ID(lines);

		return PaymentAllocationId.ofRepoId(allocationHdr.getC_AllocationHdr_ID());
	}

	/**
	 * Sets the counter allocation line - that means the mathcing line
	 * The id is set only if we have 2 line: credit memo - invoice; purchase invoice - sales invoice; incoming payment - outgoing payment
	 *
	 * @param lines
	 */
	private static void updateCounter_AllocationLine_ID(final ImmutableList<I_C_AllocationLine> lines)
	{
		if (lines.size() != 2)
		{
			return;
		}

		//
		final I_C_AllocationLine al1 = lines.get(0);
		final I_C_AllocationLine al2 = lines.get(1);

		al1.setCounter_AllocationLine_ID(al2.getC_AllocationLine_ID());
		InterfaceWrapperHelper.save(al1);

		//
		al2.setCounter_AllocationLine_ID(al1.getC_AllocationLine_ID());
		InterfaceWrapperHelper.save(al2);
	}

	/**
	 * Allocate {@link #getPayableDocuments()} and {@link #getPaymentDocuments()}.
	 *
	 * @return created allocation candidates
	 */
	private ImmutableList<AllocationLineCandidate> createAllocationLineCandidates()
	{
		//
		// Make sure we have something to allocate
		final List<PayableDocument> payableDocuments = getPayableDocuments();
		final List<IPaymentDocument> paymentDocuments = getPaymentDocuments();
		if (payableDocuments.isEmpty() && paymentDocuments.isEmpty())
		{
			throw new NoDocumentsPaymentAllocationException();
		}

		//
		// Make sure that we allow allocation one document per type for vendor documents
		assertOnlyOneVendorDocType(payableDocuments, paymentDocuments);

		final ImmutableList.Builder<AllocationLineCandidate> allocationCandidates = ImmutableList.builder();

		//
		// Try to allocate credit memos to regular invoices
		allocationCandidates.addAll(createAllocationLineCandidates_CreditMemosToInvoices(payableDocuments));

		//
		// Try to allocate purchase invoices to sales invoices
		if (allowPurchaseSalesInvoiceCompensation)
		{
			allocationCandidates.addAll(createAllocationLineCandidates_PurchaseInvoicesToSaleInvoices(payableDocuments));
		}

		//
		// Allocate payments to invoices
		allocationCandidates.addAll(createAllocationLineCandidates(
				AllocationLineCandidateType.InvoiceToPayment,
				payableDocuments,
				paymentDocuments));

		//
		// Try allocate payment reversals to payments
		allocationCandidates.addAll(createAllocationLineCandidates_ForPayments(paymentDocuments));

		// Try allocate the payable remaining Discounts and WriteOffs.
		allocationCandidates.addAll(createAllocationLineCandidates_DiscountAndWriteOffs(payableDocuments));

		return allocationCandidates.build();
	}

	/***
	 * Do not allow to allocate more then one document type for vendor documents
	 *
	 * @param payableDocuments
	 * @param paymentDocuments
	 */
	private void assertOnlyOneVendorDocType(
			final List<PayableDocument> payableDocuments,
			final List<IPaymentDocument> paymentDocuments)
	{
		if (!allowOnlyOneVendorDoc)
		{
			return; // task 09558: nothing to do
		}

		final List<IPaymentDocument> paymentVendorDocuments = paymentDocuments.stream()
				.filter(paymentDocument -> paymentDocument.getPaymentDirection().isOutboundPayment())
				.collect(ImmutableList.toImmutableList());

		final List<PayableDocument> payableVendorDocuments_NoCreditMemos = new ArrayList<>();
		final List<IPaymentDocument> paymentVendorDocuments_CreditMemos = new ArrayList<>();
		for (final PayableDocument payable : payableDocuments)
		{
			if (!payable.getSoTrx().isPurchase())
			{
				continue;
			}

			if (payable.isCreditMemo())
			{
				paymentVendorDocuments_CreditMemos.add(CreditMemoInvoiceAsPaymentDocumentWrapper.wrap(payable));
			}
			else
			{
				payableVendorDocuments_NoCreditMemos.add(payable);

			}
		}

		if (paymentVendorDocuments.size() > 1
				|| payableVendorDocuments_NoCreditMemos.size() > 1
				|| paymentVendorDocuments_CreditMemos.size() > 1)
		{
			final List<IPaymentDocument> paymentVendorDocs = new ArrayList<>();
			paymentVendorDocs.addAll(paymentVendorDocuments);
			paymentVendorDocs.addAll(paymentVendorDocuments_CreditMemos);

			throw new MultipleVendorDocumentsException(paymentVendorDocs, payableVendorDocuments_NoCreditMemos);
		}
	}

	/**
	 * Allocate given payments to given payable documents.
	 *
	 * @param payableDocuments
	 * @param paymentDocuments
	 * @return created allocation candidates
	 */
	private final List<AllocationLineCandidate> createAllocationLineCandidates(
			@NonNull final AllocationLineCandidateType type,
			@NonNull final List<PayableDocument> payableDocuments,
			@NonNull final List<IPaymentDocument> paymentDocuments)
	{
		if (payableDocuments.isEmpty() || paymentDocuments.isEmpty())
		{
			return ImmutableList.of();
		}

		final List<AllocationLineCandidate> allocationLineCandidates = new ArrayList<>();

		for (final PayableDocument payable : payableDocuments)
		{
			for (final IPaymentDocument payment : paymentDocuments)
			{
				// If the invoice was fully allocated, stop here and go to next invoice.
				if (payable.isFullyAllocated())
				{
					break;
				}

				// Skip fully allocated payments
				if (payment.isFullyAllocated())
				{
					continue;
				}

				// Skip if the invoice and payment are not compatible
				if (!isCompatible(payable, payment))
				{
					continue;
				}

				//
				// Calculate the amounts to allocate:
				final AllocationAmounts payableAmountsToAllocate = calculateAmountToAllocate(payable, payment);
				final Money payableOverUnderAmt = payable.computeProjectedOverUnderAmt(payableAmountsToAllocate);
				final Money paymentOverUnderAmt = payment.calculateProjectedOverUnderAmt(payableAmountsToAllocate.getPayAmt());

				// Create new Allocation Line
				final AllocationLineCandidate allocationLine = AllocationLineCandidate.builder()
						.type(type)
						//
						.orgId(payable.getOrgId())
						.bpartnerId(payable.getBpartnerId())
						//
						.payableDocumentRef(payable.getReference())
						.paymentDocumentRef(payment.getReference())
						// Amounts:
						.amounts(payableAmountsToAllocate)
						.payableOverUnderAmt(payableOverUnderAmt)
						.paymentOverUnderAmt(paymentOverUnderAmt)
						//
						.build();
				allocationLineCandidates.add(allocationLine);

				// Update how much was allocated on current invoice and payment.
				payable.addAllocatedAmounts(payableAmountsToAllocate);
				payment.addAllocatedAmt(payableAmountsToAllocate.getPayAmt());
			}	// loop through payments for current payable (aka invoice or prepay order)

			if (!payable.isFullyAllocated())
			{
				final AllocationLineCandidate allocationLine = createAllocationLineCandidate_ForRemainingOpenAmt(payable);
				if (allocationLine != null)
				{
					allocationLineCandidates.add(allocationLine);
				}
			}
		}   // payables loop (aka invoice or prepay order)

		return allocationLineCandidates;
	}

	private final AllocationLineCandidate createAllocationLineCandidate_ForRemainingOpenAmt(@NonNull final PayableDocument payable)
	{
		if (payable.isFullyAllocated())
		{
			return null;
		}

		if (PayableRemainingOpenAmtPolicy.DO_NOTHING.equals(payableRemainingOpenAmtPolicy))
		{
			// do nothing
			return null;
		}
		else if (PayableRemainingOpenAmtPolicy.DISCOUNT.equals(payableRemainingOpenAmtPolicy))
		{
			payable.moveRemainingOpenAmtToDiscount();

			final AllocationAmounts discountAndWriteOffAmts = payable.getAmountsToAllocate();
			return createAllocationLineCandidate_DiscountAndWriteOff(payable, discountAndWriteOffAmts);
		}
		else if (PayableRemainingOpenAmtPolicy.WRITE_OFF.equals(payableRemainingOpenAmtPolicy))
		{
			payable.moveRemainingOpenAmtToWriteOff();

			final AllocationAmounts discountAndWriteOffAmts = payable.getAmountsToAllocate();
			return createAllocationLineCandidate_DiscountAndWriteOff(payable, discountAndWriteOffAmts);
		}
		else
		{
			throw new AdempiereException("Unknown payableRemainingOpenAmtPolicy: " + payableRemainingOpenAmtPolicy); // shall not happen
		}
	}

	private final List<AllocationLineCandidate> createAllocationLineCandidates_CreditMemosToInvoices(final List<PayableDocument> payableDocuments)
	{
		final List<PayableDocument> invoices = new ArrayList<>();
		final List<IPaymentDocument> creditMemos = new ArrayList<>();

		for (final PayableDocument payable : payableDocuments)
		{
			if (payable.isCreditMemo())
			{
				creditMemos.add(CreditMemoInvoiceAsPaymentDocumentWrapper.wrap(payable));
			}
			else
			{
				invoices.add(payable);
			}
		}

		return createAllocationLineCandidates(
				AllocationLineCandidateType.InvoiceToCreditMemo,
				invoices,
				creditMemos);
	}

	private final List<AllocationLineCandidate> createAllocationLineCandidates_PurchaseInvoicesToSaleInvoices(final List<PayableDocument> payableDocuments)
	{
		final List<PayableDocument> salesInvoices = new ArrayList<>();
		final List<IPaymentDocument> purchaseInvoices = new ArrayList<>();

		for (final PayableDocument payable : payableDocuments)
		{
			// do not support credit memo
			if (payable.isCreditMemo())
			{
				continue;
			}

			if (payable.getSoTrx().isSales())
			{
				salesInvoices.add(payable);
			}
			else
			{
				purchaseInvoices.add(PurchaseInvoiceAsInboundPaymentDocumentWrapper.wrap(payable));
			}
		}

		return createAllocationLineCandidates(
				AllocationLineCandidateType.SalesInvoiceToPurchaseInvoice,
				salesInvoices,
				purchaseInvoices);
	}

	/**
	 * Iterate all payment documents and try to allocate incoming payments to outgoing payments
	 *
	 * @param paymentDocuments
	 */
	private final List<AllocationLineCandidate> createAllocationLineCandidates_ForPayments(final List<IPaymentDocument> paymentDocuments)
	{
		if (paymentDocuments.isEmpty())
		{
			return ImmutableList.of();
		}

		//
		// Build the incoming payments and outgoing payments lists.
		final List<IPaymentDocument> paymentDocumentsIn = new ArrayList<>();
		final List<IPaymentDocument> paymentDocumentsOut = new ArrayList<>();
		for (final IPaymentDocument payment : paymentDocuments)
		{
			if (payment.isFullyAllocated())
			{
				continue;
			}
			if (payment.getAmountToAllocate().signum() > 0)
			{
				paymentDocumentsIn.add(payment);
			}
			else
			{
				paymentDocumentsOut.add(payment);
			}
		}
		// No documents => nothing to do
		if (paymentDocumentsIn.isEmpty() || paymentDocumentsOut.isEmpty())
		{
			return ImmutableList.of();
		}

		//
		// Iterate incoming payments and try allocating them to outgoing payments.
		final List<AllocationLineCandidate> allocationLineCandidates = new ArrayList<>();
		for (final IPaymentDocument paymentIn : paymentDocumentsIn)
		{
			for (final IPaymentDocument paymentOut : paymentDocumentsOut)
			{
				if (paymentOut.isFullyAllocated())
				{
					continue;
				}

				final Money paymentOut_amtToAllocate = paymentOut.getAmountToAllocate(); // => negative
				final Money paymentIn_amtToAllocate = paymentIn.getAmountToAllocate().negate(); // => negative
				final Money amtToAllocate = paymentIn_amtToAllocate.max(paymentOut_amtToAllocate);

				final Money payableOverUnderAmt = paymentOut.calculateProjectedOverUnderAmt(amtToAllocate);
				final Money paymentOverUnderAmt = paymentIn.calculateProjectedOverUnderAmt(amtToAllocate.negate()).negate();

				// Create new Allocation Line
				final AllocationLineCandidate allocationLine = AllocationLineCandidate.builder()
						.type(AllocationLineCandidateType.InboundPaymentToOutboundPayment)
						//
						.orgId(paymentOut.getOrgId())
						.bpartnerId(paymentOut.getBpartnerId())
						//
						.payableDocumentRef(paymentOut.getReference())
						.paymentDocumentRef(paymentIn.getReference())
						// Amounts:
						.amounts(AllocationAmounts.ofPayAmt(amtToAllocate))
						.payableOverUnderAmt(payableOverUnderAmt)
						.paymentOverUnderAmt(paymentOverUnderAmt)
						//
						.build();
				allocationLineCandidates.add(allocationLine);

				// Update how much was allocated on current invoice and payment.
				paymentOut.addAllocatedAmt(amtToAllocate);
				paymentIn.addAllocatedAmt(amtToAllocate.negate());
			}
		}

		return allocationLineCandidates;
	}

	/**
	 * Iterate all given payable documents and create an allocation only for Discount and WriteOff amounts.
	 *
	 * @param payableDocuments
	 * @return created allocation candidates.
	 */
	private List<AllocationLineCandidate> createAllocationLineCandidates_DiscountAndWriteOffs(final List<PayableDocument> payableDocuments)
	{
		if (payableDocuments.isEmpty())
		{
			return ImmutableList.of();
		}

		final List<AllocationLineCandidate> allocationLineCandidates = new ArrayList<>();
		for (final PayableDocument payable : payableDocuments)
		{
			final AllocationAmounts amountsToAllocate = payable.getAmountsToAllocate().withZeroPayAmt();
			if (amountsToAllocate.isZero())
			{
				continue;
			}

			final AllocationLineCandidate allocationLine = createAllocationLineCandidate_DiscountAndWriteOff(payable, amountsToAllocate);
			allocationLineCandidates.add(allocationLine);
		}

		return allocationLineCandidates;
	}

	private AllocationLineCandidate createAllocationLineCandidate_DiscountAndWriteOff(
			@NonNull final PayableDocument payable,
			@NonNull final AllocationAmounts amountsToAllocate)
	{
		Check.assume(amountsToAllocate.getPayAmt().signum() == 0, "PayAmt shall be zero: {}", amountsToAllocate);

		final Money payableOverUnderAmt = payable.computeProjectedOverUnderAmt(amountsToAllocate);
		final AllocationLineCandidate allocationLine = AllocationLineCandidate.builder()
				.type(AllocationLineCandidateType.InvoiceDiscountOrWriteOff)
				//
				.orgId(payable.getOrgId())
				.bpartnerId(payable.getBpartnerId())
				//
				.payableDocumentRef(payable.getReference())
				.paymentDocumentRef(null) // nop
				// Amounts:
				.amounts(amountsToAllocate)
				.payableOverUnderAmt(payableOverUnderAmt)
				// .paymentOverUnderAmt(ZERO)
				//
				.build();

		payable.addAllocatedAmounts(amountsToAllocate);

		return allocationLine;
	}

	private final OptionalDeferredException<PaymentAllocationException> checkFullyAllocated()
	{
		//
		// Check payables (invoices, prepaid orders etc)
		{
			final List<PayableDocument> payableDocumentsNotFullyAllocated = getPayableDocumentsNotFullyAllocated();
			if (!payableDocumentsNotFullyAllocated.isEmpty())
			{
				return OptionalDeferredException.of(() -> new PayableDocumentNotAllocatedException(payableDocumentsNotFullyAllocated));
			}
		}

		//
		// Check payments
		{
			final List<IPaymentDocument> paymentDocumentsNotFullyAllocated = getPaymentDocumentsNotFullyAllocated();
			if (!paymentDocumentsNotFullyAllocated.isEmpty())
			{
				return OptionalDeferredException.of(() -> new PaymentDocumentNotAllocatedException(paymentDocumentsNotFullyAllocated));
			}
		}

		return OptionalDeferredException.noError();
	}

	private List<IPaymentDocument> getPaymentDocumentsNotFullyAllocated()
	{
		return getPaymentDocuments()
				.stream()
				.filter(payment -> !payment.isFullyAllocated())
				.collect(ImmutableList.toImmutableList());
	}

	private List<PayableDocument> getPayableDocumentsNotFullyAllocated()
	{
		return getPayableDocuments()
				.stream()
				.filter(payable -> !payable.isFullyAllocated())
				.collect(ImmutableList.toImmutableList());
	}

	/**
	 * Check if given payment document can be allocated to payable document.
	 *
	 * @param payable
	 * @param payment
	 * @return true if the invoice and payment are compatible and we could try to do an allocation
	 */
	private static final boolean isCompatible(final PayableDocument payable, final IPaymentDocument payment)
	{
		// Given payment does not support payable's type
		if (!payment.canPay(payable))
		{
			return false;
		}

		//
		// Check invoice-payment compatibility: same sign
		final boolean positiveInvoiceAmtToAllocate = payable.getAmountsToAllocateInitial().getPayAmt().signum() >= 0;
		final boolean positivePaymentAmtToAllocate = payment.getAmountToAllocateInitial().signum() >= 0;
		if (positiveInvoiceAmtToAllocate != positivePaymentAmtToAllocate)
		{
			return false;
		}

		//
		// Check invoice-payment compatibility: same BPartner
		// NOTE: we don't check this because we are allowed to allocate invoice-payments of different BPartners
		// Think about BP relations.

		return true;
	}

	/**
	 *
	 * @param invoice
	 * @param payment
	 * @return how much we maximum allocate between given invoice and given payment.
	 */
	private final AllocationAmounts calculateAmountToAllocate(final PayableDocument invoice, final IPaymentDocument payment)
	{
		final AllocationAmounts invoiceAmountsToAllocate = invoice.getAmountsToAllocate();
		final Money invoicePayAmtToAllocate = invoiceAmountsToAllocate.getPayAmt();
		final Money paymentAmountToAllocate = payment.getAmountToAllocate();

		if (invoicePayAmtToAllocate.signum() >= 0)
		{
			// Invoice(+), Payment(+)
			if (paymentAmountToAllocate.signum() >= 0)
			{
				final Money payAmt = invoicePayAmtToAllocate.min(paymentAmountToAllocate);
				return invoiceAmountsToAllocate.withPayAmt(payAmt);
			}
			// Invoice(+), Payment(-)
			else
			{
				return invoiceAmountsToAllocate.withZeroPayAmt();
			}
		}
		else
		{
			// Invoice(-), Payment(+)
			if (paymentAmountToAllocate.signum() >= 0)
			{
				return invoiceAmountsToAllocate.withZeroPayAmt();
			}
			// Invoice(-), Payment(-)
			else
			{
				final Money payAmt = invoicePayAmtToAllocate.max(paymentAmountToAllocate);
				return invoiceAmountsToAllocate.withPayAmt(payAmt);
			}
		}
	}

	private final void markAsBuilt()
	{
		assertNotBuilt();
		_built = true;
	}

	private final void assertNotBuilt()
	{
		Check.assume(!_built, "Not already built");
	}

	private final LocalDate getDateTrx()
	{
		Check.assumeNotNull(_dateTrx, "date not null");
		return _dateTrx;
	}

	public PaymentAllocationBuilder dateTrx(final LocalDate dateTrx)
	{
		assertNotBuilt();
		_dateTrx = dateTrx;
		return this;
	}

	private final LocalDate getDateAcct()
	{
		Check.assumeNotNull(_dateAcct, "date not null");
		return _dateAcct;
	}

	public PaymentAllocationBuilder dateAcct(final LocalDate dateAcct)
	{
		assertNotBuilt();
		_dateAcct = dateAcct;
		return this;
	}

	public PaymentAllocationBuilder allowOnlyOneVendorDoc(final boolean allowOnlyOneVendorDoc)
	{
		assertNotBuilt();
		this.allowOnlyOneVendorDoc = allowOnlyOneVendorDoc;
		return this;
	}

	public PaymentAllocationBuilder allowPartialAllocations(final boolean allowPartialAllocations)
	{
		assertNotBuilt();
		this.allowPartialAllocations = allowPartialAllocations;
		return this;
	}

	public PaymentAllocationBuilder allowPurchaseSalesInvoiceCompensation(final boolean allowPurchaseSalesInvoiceCompensation)
	{
		assertNotBuilt();
		this.allowPurchaseSalesInvoiceCompensation = allowPurchaseSalesInvoiceCompensation;
		return this;
	}

	public PaymentAllocationBuilder payableRemainingOpenAmtPolicy(@NonNull final PayableRemainingOpenAmtPolicy payableRemainingOpenAmtPolicy)
	{
		assertNotBuilt();
		this.payableRemainingOpenAmtPolicy = payableRemainingOpenAmtPolicy;
		return this;
	}

	public PaymentAllocationBuilder dryRun()
	{
		this.dryRun = true;
		return this;
	}

	public PaymentAllocationBuilder payableDocuments(final Collection<PayableDocument> payableDocuments)
	{
		_payableDocuments = ImmutableList.copyOf(payableDocuments);
		return this;
	}

	public PaymentAllocationBuilder paymentDocuments(final Collection<PaymentDocument> paymentDocuments)
	{
		_paymentDocuments = ImmutableList.copyOf(paymentDocuments);
		return this;
	}

	public PaymentAllocationBuilder paymentDocument(final PaymentDocument paymentDocument)
	{
		return paymentDocuments(ImmutableList.of(paymentDocument));
	}

	private final List<PayableDocument> getPayableDocuments()
	{
		return _payableDocuments;
	}

	private final List<IPaymentDocument> getPaymentDocuments()
	{
		return _paymentDocuments;
	}
}
