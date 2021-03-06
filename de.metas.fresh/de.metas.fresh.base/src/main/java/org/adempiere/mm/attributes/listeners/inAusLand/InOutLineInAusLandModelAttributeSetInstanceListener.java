package org.adempiere.mm.attributes.listeners.inAusLand;

/*
 * #%L
 * de.metas.fresh.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import java.util.Collections;
import java.util.List;

import org.adempiere.mm.attributes.api.IInAusLandAttributeBL;
import org.adempiere.mm.attributes.api.IModelAttributeSetInstanceListener;
import org.adempiere.mm.attributes.countryattribute.impl.CountryAwareAttributeUpdater;
import org.adempiere.mm.attributes.countryattribute.impl.InOutLineCountryAware;
import org.compiere.model.I_M_InOutLine;

import de.metas.util.Services;

public class InOutLineInAusLandModelAttributeSetInstanceListener implements IModelAttributeSetInstanceListener
{
	@Override
	public String getSourceTableName()
	{
		return I_M_InOutLine.Table_Name;
	}

	@Override
	public List<String> getSourceColumnNames()
	{
		return Collections.emptyList();
	}

	@Override
	public void modelChanged(final Object model)
	{
		new CountryAwareAttributeUpdater()
				.setCountryAwareFactory(InOutLineCountryAware.factory)
				.setCountryAwareAttributeService(Services.get(IInAusLandAttributeBL.class))
				.setSourceModel(model)
				.updateASI();
	}
}
