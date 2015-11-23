/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.fields;

import org.junit.Ignore;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.FromDataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static java.time.Month.*;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.Assert.assertThat;

@RunWith(Theories.class)
public class MonthYearTest
{
    private MonthYear monthYear = new MonthYear();

    @DataPoints("validMonthYears")
    public static Iterable<Object[]> validMonthYears()
    {
        return Arrays.asList(
            new Object[]{"000101", MonthYear.of(1, JANUARY) },
            new Object[]{"201502", MonthYear.of(2015, FEBRUARY) },
            new Object[]{"999912", MonthYear.of(9999, DECEMBER) }
        );
    }

    @DataPoints("validMonthYearsWithDay")
    public static Iterable<Object[]> validMonthYearsWithDay()
    {
        return Arrays.asList(
            new Object[]{"00010101", MonthYear.withDayOfMonth(1, JANUARY, 1) },
            new Object[]{"20150225", MonthYear.withDayOfMonth(2015, FEBRUARY, 25) },
            new Object[]{"99991231", MonthYear.withDayOfMonth(9999, DECEMBER, 31) }
        );
    }

    @DataPoints("validMonthYearsWithWeek")
    public static Iterable<Object[]> validMonthYearsWithWeek()
    {
        return Arrays.asList(
            new Object[]{"000101w1", MonthYear.withWeekOfMonth(1, JANUARY, 1) },
            new Object[]{"201502w5", MonthYear.withWeekOfMonth(2015, FEBRUARY, 5) },
            new Object[]{"999912w3", MonthYear.withWeekOfMonth(9999, DECEMBER, 3) }
        );
    }

    @DataPoints("invalidMonthYearsWithDay")
    public static Iterable<Object[]> invalidMonthYearsWithDay()
    {
        return Arrays.asList(
            new Object[]{"00010101"},
            new Object[]{"20150225"},
            new Object[]{"99991231"}
        );
    }

    @Theory
    public void shouldToStringValidDates(@FromDataPoints("validMonthYears") final Object[] data)
    {
        final String input = (String) data[0];
        final MonthYear expectedMonthYear = (MonthYear) data[1];

        assertThat(expectedMonthYear, hasToString(input));
    }

    @Theory
    public void shouldToStringValidDatesWithDay(@FromDataPoints("validMonthYearsWithDay") final Object[] data)
    {
        final String input = (String) data[0];
        final MonthYear expectedMonthYear = (MonthYear) data[1];

        assertThat(expectedMonthYear, hasToString(input));
    }

    @Theory
    public void shouldToStringValidDatesWithWeek(@FromDataPoints("validMonthYearsWithDay") final Object[] data)
    {
        final String input = (String) data[0];
        final MonthYear expectedMonthYear = (MonthYear) data[1];

        assertThat(expectedMonthYear, hasToString(input));
    }

    @Ignore
    @Theory
    public void shouldDecodeValidDatesWithDay(@FromDataPoints("validMonthYearsWithWeek") final Object[] data)
    {
        final String input = (String) data[0];
        final MonthYear expectedMonthYear = (MonthYear) data[1];

        System.out.println(input);
        System.out.println(expectedMonthYear);
    }

}
