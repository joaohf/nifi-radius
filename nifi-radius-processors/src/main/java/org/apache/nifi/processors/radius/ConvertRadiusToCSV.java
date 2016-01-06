/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.radius;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.rmi.UnexpectedException;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.*;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.LongHolder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

@SideEffectFree
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@Tags({"radius", "csv"})
@CapabilityDescription("Convert Radius log to CSV")
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class ConvertRadiusToCSV extends AbstractProcessor {

    private static final Relationship SUCCESS = new Relationship.Builder()
        .name("success")
        .description("Radius content that was converted successfully to CSV")
        .build();

    private static final Relationship FAILURE = new Relationship.Builder()
        .name("failure")
        .description("Radius content that could not be processed")
        .build();

    private static final Relationship INCOMPATIBLE = new Relationship.Builder()
        .name("incompatible")
        .description("Radius content that could not be converted")
        .build();

    private List<PropertyDescriptor> PROPERTIES;

    private static final Set<Relationship> RELATIONSHIPS = ImmutableSet.<Relationship> builder()
        .add(SUCCESS)
        .add(FAILURE)
        .add(INCOMPATIBLE)
        .build();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if ( flowFile == null ) {
            return;
        }

        try {
            final LongHolder written = new LongHolder(0L);

            FlowFile badRecords = session.clone(flowFile);
            FlowFile outgoingCsv = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(InputStream in, OutputStream out) throws IOException {

                    String csv = IOUtils.toString(in);

                    String output = getCsvFormat(csv);

                    out.write(output.getBytes());

                    written.incrementAndGet();
                }
            });

            long errors = 0;

            session.adjustCounter("Converted records", written.get(),
                    false /* update only if file transfer is successful */);
            session.adjustCounter("Conversion errors", errors,
                    false /* update only if file transfer is successful */);

            if (written.get() > 0L) {
                session.transfer(outgoingCsv, SUCCESS);

                if (errors > 0L) {
                    getLogger().warn("Failed to convert {}/{} records from CSV to Avro",
                            new Object[]{errors, errors + written.get()});
                    session.transfer(badRecords, INCOMPATIBLE);
                } else {
                    session.remove(badRecords);
                }

            } else {
                session.remove(outgoingCsv);

                if (errors > 0L) {
                    getLogger().warn("Failed to convert {}/{} records from CSV to Avro",
                            new Object[]{errors, errors});
                } else {
                    badRecords = session.putAttribute(
                            badRecords, "errors", "No incoming records");
                }

                session.transfer(badRecords, FAILURE);
            }

        } catch (ProcessException e) {
            getLogger().error("Failed reading or writing", e);
            session.transfer(flowFile, FAILURE);
        }
    }

    private static String COMMA = ", ";

    /**
     * Generate a String as CSV format
     * @param content String wiht specift format
     * @return String with header at first line and the value at second line
     */
    private static String getCsvFormat(final String content) {

        //String [] fileString = content.split("\\n");
        String [] fileString = content.split("\\t"); //Split by anything common to break as line

        StringBuilder csvHeader = new StringBuilder("Time,");
        StringBuilder csvValue = new StringBuilder();

        if (fileString != null) {
            boolean isFirstLine = true;

            for (String line : fileString) {
                if (isFirstLine) {
                    csvValue.append(line);
                    csvValue.append(COMMA);
                    isFirstLine = false;
                    continue;
                }
                String [] lineValue = line.split("=");
                csvHeader.append(lineValue[0].trim());
                csvHeader.append(COMMA);
                csvValue.append(lineValue[1].trim());
                csvValue.append(COMMA);
            }
        }

        //Remove the last comma
        csvHeader = csvHeader.deleteCharAt(csvHeader.length() - 2);
        csvValue = csvValue.deleteCharAt(csvValue.length() - 2);

        //Mont the string to be return
        String csvString = csvHeader.toString() + System.lineSeparator() + csvValue.toString();

        return csvString;
    }
}
