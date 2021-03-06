// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.statistics.impl;

import com.intellij.CommonBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.statistics.StatisticsInfo;
import com.intellij.psi.statistics.StatisticsManager;
import com.intellij.reference.SoftReference;
import com.intellij.util.NotNullFunction;
import com.intellij.util.ScrambledInputStream;
import com.intellij.util.ScrambledOutputStream;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.io.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class StatisticsManagerImpl extends StatisticsManager {
  private static final int UNIT_COUNT = 997;
  private static final Object LOCK = new Object();

  @NonNls private static final String STORE_PATH = PathManager.getSystemPath() + File.separator + "stat";

  private final List<SoftReference<StatisticsUnit>> myUnits = ContainerUtil.newArrayList(Collections.nCopies(UNIT_COUNT, null));
  private final HashSet<StatisticsUnit> myModifiedUnits = new HashSet<>();
  private boolean myTestingStatistics;

  @Override
  public int getUseCount(@NotNull final StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return 0;

    int useCount = 0;

    for (StatisticsInfo conjunct : info.getConjuncts()) {
      useCount = Math.max(doGetUseCount(conjunct), useCount);
    }

    return useCount;
  }

  private int doGetUseCount(StatisticsInfo info) {
    String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      return unit.getData(key1, info.getValue());
    }
  }

  @Override
  public int getLastUseRecency(@NotNull StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return 0;

    int recency = Integer.MAX_VALUE;
    for (StatisticsInfo conjunct : info.getConjuncts()) {
      recency = Math.min(doGetRecency(conjunct), recency);
    }
    return recency;
  }

  private int doGetRecency(StatisticsInfo info) {
    String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      return unit.getRecency(key1, info.getValue());
    }
  }

  @Override
  public void incUseCount(@NotNull final StatisticsInfo info) {
    if (info == StatisticsInfo.EMPTY) return;
    if (ApplicationManager.getApplication().isUnitTestMode() && !myTestingStatistics) {
      return;
    }

    ApplicationManager.getApplication().assertIsDispatchThread();

    for (StatisticsInfo conjunct : info.getConjuncts()) {
      doIncUseCount(conjunct);
    }
  }

  private void doIncUseCount(StatisticsInfo info) {
    final String key1 = info.getContext();
    int unitNumber = getUnitNumber(key1);
    synchronized (LOCK) {
      StatisticsUnit unit = getUnit(unitNumber);
      unit.incData(key1, info.getValue());
      myModifiedUnits.add(unit);
    }
  }

  @Override
  public StatisticsInfo[] getAllValues(final String context) {
    final String[] strings;
    synchronized (LOCK) {
      strings = getUnit(getUnitNumber(context)).getKeys2(context);
    }
    return ContainerUtil.map2Array(strings, StatisticsInfo.class, (NotNullFunction<String, StatisticsInfo>)s -> new StatisticsInfo(context, s));
  }

  @Override
  public void save() {
    synchronized (LOCK) {
      if (!ApplicationManager.getApplication().isUnitTestMode()){
        ApplicationManager.getApplication().assertIsDispatchThread();
        for (StatisticsUnit unit : myModifiedUnits) {
          saveUnit(unit.getNumber());
        }
      }
      myModifiedUnits.clear();
    }
  }

  private StatisticsUnit getUnit(int unitNumber) {
    StatisticsUnit unit = SoftReference.dereference(myUnits.get(unitNumber));
    if (unit != null) return unit;
    unit = loadUnit(unitNumber);
    if (unit == null){
      unit = new StatisticsUnit(unitNumber);
    }
    myUnits.set(unitNumber, new SoftReference<>(unit));
    return unit;
  }

  private static StatisticsUnit loadUnit(int unitNumber) {
    StatisticsUnit unit = new StatisticsUnit(unitNumber);
    if (!ApplicationManager.getApplication().isUnitTestMode()){
      String path = getPathToUnit(unitNumber);
      try (InputStream in = new ScrambledInputStream(new BufferedInputStream(new FileInputStream(path)))) {
        unit.read(in);
      }
      catch(IOException | WrongFormatException ignored){
      }
    }
    return unit;
  }

  private void saveUnit(int unitNumber){
    if (!createStoreFolder()) return;
    StatisticsUnit unit = getUnit(unitNumber);
    String path = getPathToUnit(unitNumber);
    try (OutputStream out = new ScrambledOutputStream(new BufferedOutputStream(new FileOutputStream(path)))) {
      unit.write(out);
    }
    catch(IOException e){
      Messages.showMessageDialog(
        IdeBundle.message("error.saving.statistics", e.getLocalizedMessage()),
        CommonBundle.getErrorTitle(),
        Messages.getErrorIcon()
      );
    }
  }

  private static int getUnitNumber(String key1) {
    return Math.abs(key1.hashCode() % UNIT_COUNT);
  }

  private static boolean createStoreFolder(){
    File homeFile = new File(STORE_PATH);
    if (!homeFile.exists()){
      if (!homeFile.mkdirs()){
        Messages.showMessageDialog(
          IdeBundle.message("error.saving.statistic.failed.to.create.folder", STORE_PATH),
          CommonBundle.getErrorTitle(),
          Messages.getErrorIcon()
        );
        return false;
      }
    }
    return true;
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  private static String getPathToUnit(int unitNumber) {
    return STORE_PATH + File.separator + "unit." + unitNumber;
  }

  @TestOnly
  public void enableStatistics(@NotNull Disposable parentDisposable) {
    myTestingStatistics = true;
    Disposer.register(parentDisposable, new Disposable() {
      @Override
      public void dispose() {
        synchronized (LOCK) {
          Collections.fill(myUnits, null);
        }
        myTestingStatistics = false;
      }
    });
  }

}