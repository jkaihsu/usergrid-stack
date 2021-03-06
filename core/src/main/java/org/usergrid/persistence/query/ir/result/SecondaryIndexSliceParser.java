/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.persistence.query.ir.result;


import java.nio.ByteBuffer;
import java.util.UUID;

import me.prettyprint.hector.api.beans.DynamicComposite;


/**
 * Parser for reading and writing secondary index composites
 *
 * @author tnine
 */
public class SecondaryIndexSliceParser implements SliceParser {


    /* (non-Javadoc)
     * @see org.usergrid.persistence.query.ir.result.SliceParser#parse(java.nio.ByteBuffer)
     */
    @Override
    public ScanColumn parse( ByteBuffer buff ) {
        DynamicComposite composite = DynamicComposite.fromByteBuffer( buff.duplicate() );

        return new SecondaryIndexColumn( ( UUID ) composite.get( 2 ), composite.get( 1 ), buff );
    }


    public static class SecondaryIndexColumn extends AbstractScanColumn {

        private final Object value;


        public SecondaryIndexColumn( UUID uuid, Object value, ByteBuffer buff ) {
            super( uuid, buff );
            this.value = value;
        }


        /** Get the value from the node */
        public Object getValue() {
            return this.value;
        }
    }
}
