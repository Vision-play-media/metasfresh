package de.metas.async.processor.impl;

import java.util.ArrayList;
import java.util.List;

import org.adempiere.service.ClientId;
import org.slf4j.Logger;

import de.metas.async.QueueWorkPackageId;
import de.metas.async.api.IWorkpackageLogsRepository;
import de.metas.async.api.WorkpackageLogEntry;
import de.metas.error.IErrorManager;
import de.metas.error.LoggableWithThrowableUtil;
import de.metas.error.LoggableWithThrowableUtil.FormattedMsgWithAdIssueId;
import de.metas.logging.LogManager;
import de.metas.user.UserId;
import de.metas.util.Check;
import de.metas.util.ILoggable;
import de.metas.util.Services;
import de.metas.util.time.SystemTime;
import lombok.Builder;
import lombok.NonNull;
import lombok.ToString;

/*
 * #%L
 * de.metas.async
 * %%
 * Copyright (C) 2020 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@ToString
final class WorkpackageLoggable implements ILoggable
{
	private static final Logger logger = LogManager.getLogger(WorkpackageLoggable.class);

	private final IWorkpackageLogsRepository logsRepository;
	private final IErrorManager errorManager = Services.get(IErrorManager.class);

	private final QueueWorkPackageId workpackageId;
	private final ClientId adClientId;
	private final UserId userId;

	private final int bufferSize;
	private List<WorkpackageLogEntry> buffer;

	@Builder
	public WorkpackageLoggable(
			@NonNull final IWorkpackageLogsRepository logsRepository,
			@NonNull final QueueWorkPackageId workpackageId,
			@NonNull final ClientId adClientId,
			@NonNull final UserId userId,
			final int bufferSize)
	{
		Check.assumeGreaterThanZero(bufferSize, "bufferSize");

		this.logsRepository = logsRepository;

		this.workpackageId = workpackageId;
		this.adClientId = adClientId;
		this.userId = userId;

		this.bufferSize = bufferSize;
		this.buffer = null;
	}

	@Override
	public ILoggable addLog(final String msg, final Object... msgParameters)
	{
		final WorkpackageLogEntry logEntry = createLogEntry(msg, msgParameters);

		List<WorkpackageLogEntry> buffer = this.buffer;
		if (buffer == null)
		{
			buffer = this.buffer = new ArrayList<>(bufferSize);
		}
		buffer.add(logEntry);

		if (buffer.size() >= bufferSize)
		{
			flush();
		}

		return this;
	}

	private WorkpackageLogEntry createLogEntry(@NonNull final String msg, final Object... msgParameters)
	{
		final FormattedMsgWithAdIssueId msgAndAdIssueId = LoggableWithThrowableUtil.extractMsgAndAdIssue(msg, msgParameters);

		return WorkpackageLogEntry.builder()
				.message(msgAndAdIssueId.getFormattedMessage())
				.adIssueId(msgAndAdIssueId.getAdIsueId().orElse(null))
				.timestamp(SystemTime.asInstant())
				.workpackageId(workpackageId)
				.adClientId(adClientId)
				.userId(userId)
				.build();
	}

	private static Throwable extractThrowable(final Object[] msgParameters)
	{
		if (msgParameters == null || msgParameters.length == 0)
		{
			return null;
		}

		final Object lastEntry = msgParameters[msgParameters.length - 1];
		return lastEntry instanceof Throwable
				? (Throwable)lastEntry
				: null;
	}

	private static Object[] removeLastElement(final Object[] msgParameters)
	{
		if (msgParameters == null || msgParameters.length == 0)
		{
			return msgParameters;
		}
		final int newLen = msgParameters.length - 1;
		final Object[] msgParametersNew = new Object[newLen];
		System.arraycopy(msgParameters, 0, msgParametersNew, 0, newLen);
		return msgParametersNew;
	}

	@Override
	public void flush()
	{
		final List<WorkpackageLogEntry> logEntries = buffer;
		this.buffer = null;

		if (logEntries == null || logEntries.isEmpty())
		{
			return;
		}

		try
		{
			logsRepository.saveLogs(logEntries);
		}
		catch (final Exception ex)
		{
			// make sure flush never fails
			logger.warn("Failed saving {} log entries but IGNORED: {}", logEntries.size(), logEntries, ex);
		}
	}
}
