/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.examples.conferencescheduling.solver;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Before;
import org.junit.Test;
import org.optaplanner.core.api.domain.solution.cloner.SolutionCloner;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.examples.common.persistence.AbstractXlsxSolutionFileIO;
import org.optaplanner.examples.conferencescheduling.app.ConferenceSchedulingApp;
import org.optaplanner.examples.conferencescheduling.domain.ConferenceSolution;
import org.optaplanner.examples.conferencescheduling.domain.Room;
import org.optaplanner.examples.conferencescheduling.domain.Talk;
import org.optaplanner.examples.conferencescheduling.domain.Timeslot;
import org.optaplanner.examples.conferencescheduling.persistence.ConferenceSchedulingXlsxFileIO;
import org.optaplanner.test.impl.score.buildin.hardsoft.HardSoftScoreVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.optaplanner.examples.common.persistence.AbstractXlsxSolutionFileIO.DAY_FORMATTER;
import static org.optaplanner.examples.common.persistence.AbstractXlsxSolutionFileIO.TIME_FORMATTER;

public class ConferenceSchedulingScoreRulesXlsxTest {

    private final String testFileName = "testConferenceSchedulingScoreRules.xlsx";
    private final HardSoftScore unassignedScore = HardSoftScore.ZERO;
    private final transient Logger logger = LoggerFactory.getLogger(getClass());

    private ConferenceSolution initialSolution;
    private String currentPackage;
    private String currentConstraint;
    private HardSoftScore expectedScore;

    private HardSoftScoreVerifier<ConferenceSolution> scoreVerifier = new HardSoftScoreVerifier<>(
            SolverFactory.createFromXmlResource(ConferenceSchedulingApp.SOLVER_CONFIG));
    private TestConferenceSchedulingScoreRulesReader testFileReader;

    @Before
    public void setup() {
        File testFile = new File(getClass().getResource(testFileName).getFile());
        try (InputStream in = new BufferedInputStream(new FileInputStream(testFile))) {
            XSSFWorkbook workbook = new XSSFWorkbook(in);
            this.initialSolution = new ConferenceSchedulingXlsxFileIO().read(testFile);
            this.testFileReader = new TestConferenceSchedulingScoreRulesReader(workbook);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed reading inputSolutionFile ("
                    + testFile.getName() + ").", e);
        }
    }

    @Test
    public void scoreRoles() {
        ConferenceSolution currentSheetSolution;
        while ((currentSheetSolution = testFileReader.nextSolution()) != null) {
            scoreVerifier.assertHardWeight(currentPackage, currentConstraint, expectedScore.getHardScore(), currentSheetSolution);
            scoreVerifier.assertSoftWeight(currentPackage, currentConstraint, expectedScore.getSoftScore(), currentSheetSolution);
        }
    }

    private class TestConferenceSchedulingScoreRulesReader extends AbstractXlsxSolutionFileIO.AbstractXlsxReader<ConferenceSolution> {

        private final SolutionCloner<ConferenceSolution> solutionCloner =
                SolutionDescriptor.buildSolutionDescriptor(ConferenceSolution.class, Talk.class).getSolutionCloner();

        private int numberOfSheets, currentTestSheetIndex;
        private Map<String, Room> roomMap;
        private Map<Pair<LocalDateTime, LocalDateTime>, Timeslot> timeslotMap;
        private Map<Integer, LocalDate> columnIndexToDateMap;
        private Map<Integer, LocalTime> columnIndexToStartTimeMap;
        private Map<Integer, LocalTime> columnIndexToEndTimeMap;

        private TestConferenceSchedulingScoreRulesReader(XSSFWorkbook workbook) {
            super(workbook);
            this.numberOfSheets = workbook.getNumberOfSheets();
            this.currentTestSheetIndex = workbook.getSheetIndex("Talks") + 1;
            roomMap = initialSolution.getRoomList().stream().collect(
                    Collectors.toMap(Room::getName, Function.identity()));
            timeslotMap = initialSolution.getTimeslotList().stream().collect(
                    Collectors.toMap(timeslot -> Pair.of(timeslot.getStartDateTime(), timeslot.getEndDateTime()),
                            Function.identity()));
            this.columnIndexToDateMap = new HashMap<>(timeslotMap.size());
            this.columnIndexToStartTimeMap = new HashMap<>(timeslotMap.size());
            this.columnIndexToEndTimeMap = new HashMap<>(timeslotMap.size());
        }

        @Override
        public ConferenceSolution read() {
            return initialSolution;
        }

        private ConferenceSolution nextSolution() {

            if (currentTestSheetIndex >= numberOfSheets) {
                return null;
            }

            nextSheet(workbook.getSheetName(currentTestSheetIndex++));

            nextRow(false);
            readHeaderCell("Constraint package");
            currentPackage = nextStringCell().getStringCellValue();
            nextRow(false);
            readHeaderCell("Constraint name");
            currentConstraint = nextStringCell().getStringCellValue();
            nextRow(false);
            readHeaderCell("Score");
            expectedScore = HardSoftScore.parseScore(nextStringCell().getStringCellValue());

            ConferenceSolution nextSheetSolution = solutionCloner.cloneSolution(initialSolution);
            Map<String, Talk> talkMap = nextSheetSolution.getTalkList().stream().collect(
                    Collectors.toMap(Talk::getCode, Function.identity()));

            logger.info(" Reading sheet ({})", currentSheet.getSheetName());
            scoreVerifier.assertHardWeight(currentPackage, currentConstraint, unassignedScore.getHardScore(), nextSheetSolution);
            scoreVerifier.assertSoftWeight(currentPackage, currentConstraint, unassignedScore.getSoftScore(), nextSheetSolution);

            nextRow();
            readTimeslotDays();
            nextRow(false);
            readHeaderCell("Room");
            readTimeslotHours();
            while (nextRow()) {
                String roomName = nextStringCell().getStringCellValue();
                Room room = roomMap.get(roomName);
                if (room == null) {
                    throw new IllegalStateException(currentPosition() + ": The room (" + roomName
                            + ") does not exist in the room list.");
                }
                for (int i = 0; i < columnIndexToStartTimeMap.size(); i++) {
                    String talkCode = nextCell().getStringCellValue();
                    if (!talkCode.isEmpty()) {
                        Talk talk = talkMap.get(talkCode);
                        if (talk == null) {
                            throw new IllegalStateException(currentPosition() + ": Talk (" + talkCode
                                    + ") does not exist in the talk list.");
                        }

                        LocalDateTime startDateTime = LocalDateTime.of(columnIndexToDateMap.get(currentColumnNumber),
                                columnIndexToStartTimeMap.get(currentColumnNumber));
                        LocalDateTime endDateTime = LocalDateTime.of(columnIndexToDateMap.get(currentColumnNumber),
                                columnIndexToEndTimeMap.get(currentColumnNumber));

                        Timeslot timeslot = timeslotMap.get(Pair.of(startDateTime, endDateTime));
                        if (timeslot == null) {
                            throw new IllegalStateException(currentPosition()
                                    + ": The timeslot with date (" + startDateTime.toLocalDate()
                                    + "), startTime (" + startDateTime.toLocalTime()
                                    + ") and endTime (" + endDateTime.toLocalTime()
                                    + ") doesn't exist in the other sheet (Timeslots).");
                        }
                        talk.setRoom(room);
                        talk.setTimeslot(timeslot);
                    }
                }
            }

            return nextSheetSolution;
        }

        private void readTimeslotDays() {
            columnIndexToDateMap.clear();
            String previousDateString = null;
            for (int i = 0; i < currentRow.getLastCellNum(); i++) {
                XSSFCell cell = currentRow.getCell(i);
                if (!cell.getStringCellValue().isEmpty() || previousDateString != null) {
                    if (!cell.getStringCellValue().isEmpty()) {
                        previousDateString = cell.getStringCellValue();
                    }
                    try {
                        columnIndexToDateMap.put(i, LocalDate.parse(previousDateString, DAY_FORMATTER));
                    } catch (DateTimeParseException e) {
                        throw new IllegalStateException(currentPosition() + ": The date (" + cell.getStringCellValue()
                                + ") does not parse as a date.");
                    }
                }
            }
        }

        private void readTimeslotHours() {
            columnIndexToStartTimeMap.clear();
            columnIndexToEndTimeMap.clear();
            StreamSupport.stream(currentRow.spliterator(), false)
                    .forEach(cell -> {
                        if (!cell.getStringCellValue().isEmpty() && !cell.getStringCellValue().equals("Room")) {
                            String[] startAndEndTimeStringArray = cell.getStringCellValue().split("-");
                            try {
                                columnIndexToStartTimeMap.put(cell.getColumnIndex(),
                                        LocalTime.parse(startAndEndTimeStringArray[0], TIME_FORMATTER));
                                columnIndexToEndTimeMap.put(cell.getColumnIndex(), LocalTime.parse(startAndEndTimeStringArray[1],
                                        TIME_FORMATTER));
                            } catch (DateTimeParseException e) {
                                throw new IllegalStateException(currentPosition() + ": The startTime (" + startAndEndTimeStringArray[0]
                                        + ") or endTime (" + startAndEndTimeStringArray[1]
                                        + ") doesn't parse as a time.", e);
                            }
                        }
                    });
        }
    }
}
