/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.orientdb.query.impl;

import java.sql.SQLException;

/**
 * Setter for 'string' value
 *
 * @author Sergey Chernolyas &lt;sergey.chernolyas@gmail.com&gt;
 */
public class StringParamValueSetter implements ParamValueSetter<String> {

	@Override
	public void setValue(int index, String value) throws SQLException {
		// preparedStatement.setString( index, value );
	}

}
