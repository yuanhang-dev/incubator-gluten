/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.rexnode;

import io.github.zhztheplayer.velox4j.expression.CastTypedExpr;
import io.github.zhztheplayer.velox4j.expression.TypedExpr;
import io.github.zhztheplayer.velox4j.type.*;

import org.apache.flink.api.java.tuple.Tuple2;

import java.util.List;
import java.util.stream.Collectors;

public class TypeUtils {
  private static final List<Class<?>> NUMERIC_TYPE_PRIORITY_LIST =
      List.of(
          TinyIntType.class,
          SmallIntType.class,
          IntegerType.class,
          BigIntType.class,
          RealType.class,
          DoubleType.class,
          DecimalType.class);

  private static int getNumericTypePriority(Type type) {
    int index = NUMERIC_TYPE_PRIORITY_LIST.indexOf(type.getClass());
    if (index == -1) {
      throw new RuntimeException("Unsupported type: " + type.getClass().getName());
    }
    return index;
  }

  public static boolean isNumericType(Type type) {
    return NUMERIC_TYPE_PRIORITY_LIST.contains(type.getClass());
  }

  public static boolean isStringType(Type type) {
    return type instanceof VarCharType;
  }

  public static List<TypedExpr> promoteTypeForArithmeticExpressions(List<TypedExpr> expressions) {
    if (expressions.isEmpty()) {
      throw new RuntimeException("No expressions found");
    }
    for (TypedExpr expr : expressions) {
      Type type = expr.getReturnType();
      if (type instanceof TimestampType) {
        // 临时抛出异常来查看调用栈和表达式信息
        StringBuilder sb = new StringBuilder();
        sb.append("TIMESTAMP found in arithmetic promotion!\n");
        sb.append("Expressions count: ").append(expressions.size()).append("\n");
        for (int i = 0; i < expressions.size(); i++) {
          TypedExpr e = expressions.get(i);
          sb.append("Expression ")
              .append(i)
              .append(": ")
              .append(e.getReturnType().getClass().getSimpleName())
              .append(" - ")
              .append(e.getReturnType())
              .append(" (")
              .append(e.getClass().getSimpleName())
              .append(")\n");
        }
        throw new RuntimeException(sb.toString());
      }
    }
    // 如果所有表达式都是相同类型，直接返回
    if (areAllSameType(expressions)) {
      return expressions;
    }

    // 分类表达式类型
    boolean hasDecimal = false;
    boolean hasTimestamp = false;
    boolean hasOtherNumeric = false;

    for (TypedExpr expr : expressions) {
      Type type = expr.getReturnType();
      if (type instanceof DecimalType) {
        hasDecimal = true;
      } else if (type instanceof TimestampType) {
        hasTimestamp = true;
      } else if (isBasicNumericType(type)) {
        hasOtherNumeric = true;
      }
    }

    // 如果包含 TimestampType，不进行类型提升，直接返回
    if (hasTimestamp) {
      return expressions;
    }

    // 如果包含 DECIMAL 类型，使用 DECIMAL 类型提升
    if (hasDecimal) {
      Type targetType = calculateSafeDecimalType(expressions);
      return expressions.stream()
          .map(
              expr ->
                  expr.getReturnType().equals(targetType)
                      ? expr
                      : CastTypedExpr.create(targetType, expr, false))
          .collect(Collectors.toList());
    }

    // 只有基础数值类型，使用原有逻辑
    if (hasOtherNumeric) {
      return promoteBasicNumericTypes(expressions);
    }

    // 其他情况直接返回
    return expressions;
  }

  private static boolean isBasicNumericType(Type type) {
    return type instanceof TinyIntType
        || type instanceof SmallIntType
        || type instanceof IntegerType
        || type instanceof BigIntType
        || type instanceof RealType
        || type instanceof DoubleType;
  }

  private static List<TypedExpr> promoteBasicNumericTypes(List<TypedExpr> expressions) {
    // 原有的数值类型提升逻辑
    Type targetType =
        expressions.stream()
            .map(
                expr -> {
                  Type returnType = expr.getReturnType();
                  int priority = getBasicNumericTypePriority(returnType);
                  return new Tuple2<>(priority, returnType);
                })
            .max((t1, t2) -> Integer.compare(t1.f0, t2.f0))
            .orElseThrow(() -> new RuntimeException("No expressions found"))
            .f1;

    return expressions.stream()
        .map(
            expr ->
                expr.getReturnType().equals(targetType)
                    ? expr
                    : CastTypedExpr.create(targetType, expr, false))
        .collect(Collectors.toList());
  }

  private static int getBasicNumericTypePriority(Type type) {
    if (type instanceof TinyIntType) return 0;
    if (type instanceof SmallIntType) return 1;
    if (type instanceof IntegerType) return 2;
    if (type instanceof BigIntType) return 3;
    if (type instanceof RealType) return 4;
    if (type instanceof DoubleType) return 5;
    return -1;
  }

  /** 计算能安全容纳所有类型的 DECIMAL 类型 */
  private static Type calculateSafeDecimalType(List<TypedExpr> expressions) {
    int maxIntegerDigits = 0; // 整数部分的最大位数
    int maxScale = 0; // 小数部分的最大位数

    for (TypedExpr expr : expressions) {
      Type type = expr.getReturnType();

      if (type instanceof DecimalType) {
        DecimalType decimal = (DecimalType) type;
        int integerDigits = decimal.getPrecision() - decimal.getScale();
        maxIntegerDigits = Math.max(maxIntegerDigits, integerDigits);
        maxScale = Math.max(maxScale, decimal.getScale());
      } else if (type instanceof BigIntType) {
        // BIGINT 范围: -9,223,372,036,854,775,808 到 9,223,372,036,854,775,807
        // 最大需要 19 位数字
        maxIntegerDigits = Math.max(maxIntegerDigits, 19);
      } else if (type instanceof IntegerType) {
        // INTEGER 范围: -2,147,483,648 到 2,147,483,647
        // 最大需要 10 位数字
        maxIntegerDigits = Math.max(maxIntegerDigits, 10);
      } else if (type instanceof SmallIntType) {
        // SMALLINT 范围: -32,768 到 32,767
        // 最大需要 5 位数字
        maxIntegerDigits = Math.max(maxIntegerDigits, 5);
      } else if (type instanceof TinyIntType) {
        // TINYINT 范围: -128 到 127
        // 最大需要 3 位数字
        maxIntegerDigits = Math.max(maxIntegerDigits, 3);
      }
      // 注意：整数类型的 scale 都是 0，不影响 maxScale
    }

    // 计算总精度
    int totalPrecision = maxIntegerDigits + maxScale;

    // 确保不超过 Velox 的 DECIMAL 精度限制 (38)
    if (totalPrecision > 38) {
      if (maxIntegerDigits >= 32) {
        // 如果整数部分就很大，限制总精度为 38，适当减少小数位数
        totalPrecision = 38;
        maxScale = Math.min(maxScale, 6); // 保留合理的小数位数
      } else {
        // 整数部分不太大，调整小数位数
        maxScale = 38 - maxIntegerDigits;
      }
      totalPrecision = maxIntegerDigits + maxScale;
    }

    return new DecimalType(totalPrecision, maxScale);
  }

  /** 检查所有表达式是否为相同类型 */
  private static boolean areAllSameType(List<TypedExpr> expressions) {
    if (expressions.size() <= 1) {
      return true;
    }

    Type firstType = expressions.get(0).getReturnType();
    return expressions.stream().allMatch(expr -> expr.getReturnType().equals(firstType));
  }
}
