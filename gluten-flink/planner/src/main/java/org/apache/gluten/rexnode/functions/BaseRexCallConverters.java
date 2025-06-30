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
package org.apache.gluten.rexnode.functions;

import org.apache.gluten.rexnode.RexConversionContext;
import org.apache.gluten.rexnode.RexNodeConverter;
import org.apache.gluten.rexnode.TypeUtils;

import io.github.zhztheplayer.velox4j.expression.CallTypedExpr;
import io.github.zhztheplayer.velox4j.expression.TypedExpr;
import io.github.zhztheplayer.velox4j.type.BigIntType;
import io.github.zhztheplayer.velox4j.type.DecimalType;
import io.github.zhztheplayer.velox4j.type.TimestampType;
import io.github.zhztheplayer.velox4j.type.Type;

import org.apache.calcite.rex.RexCall;

import java.util.List;

abstract class BaseRexCallConverter implements RexCallConverter {
  protected final String functionName;

  public BaseRexCallConverter(String functionName) {
    this.functionName = functionName;
  }

  protected List<TypedExpr> getParams(RexCall callNode, RexConversionContext context) {
    return RexNodeConverter.toTypedExpr(callNode.getOperands(), context);
  }

  protected Type getResultType(RexCall callNode) {
    return RexNodeConverter.toType(callNode.getType());
  }

  @Override
  public boolean isSupported(RexCall callNode, RexConversionContext context) {
    // Default implementation assumes all RexCall nodes are supported.
    // Subclasses can override this method to provide specific support checks.
    return true;
  }
}

class DefaultRexCallConverter extends BaseRexCallConverter {
  public DefaultRexCallConverter(String functionName) {
    super(functionName);
  }

  @Override
  public TypedExpr toTypedExpr(RexCall callNode, RexConversionContext context) {
    List<TypedExpr> params = getParams(callNode, context);
    Type resultType = getResultType(callNode);
    return new CallTypedExpr(resultType, params, functionName);
  }
}

class BasicArithmeticOperatorRexCallConverter extends BaseRexCallConverter {
  public BasicArithmeticOperatorRexCallConverter(String functionName) {
    super(functionName);
  }

  @Override
  public boolean isSupported(RexCall callNode, RexConversionContext context) {
    return callNode.getOperands().stream()
        .allMatch(param -> TypeUtils.isNumericType(RexNodeConverter.toType(param.getType())));
  }

  @Override
  public TypedExpr toTypedExpr(RexCall callNode, RexConversionContext context) {
    List<TypedExpr> params = getParams(callNode, context);

    Type resultType = getResultType(callNode);
    if (params.size() == 2) {
      Type leftType = params.get(0).getReturnType();
      Type rightType = params.get(1).getReturnType();

      // 检查是否包含 DECIMAL 类型
      boolean hasDecimal = leftType instanceof DecimalType || rightType instanceof DecimalType;

      if (hasDecimal) {
        // 使用改进的类型提升逻辑
        List<TypedExpr> alignedParams = TypeUtils.promoteTypeForArithmeticExpressions(params);

        return new CallTypedExpr(resultType, alignedParams, functionName);
      }
    }

    // 其他情况使用原有逻辑
    List<TypedExpr> alignedParams = TypeUtils.promoteTypeForArithmeticExpressions(params);
    return new CallTypedExpr(resultType, alignedParams, functionName);
  }

  /** 根据 Velox 规则计算 DECIMAL 运算的结果类型 */
  private Type calculateDecimalResultType(
      DecimalType leftType, DecimalType rightType, String operation) {
    int leftPrecision = leftType.getPrecision();
    int leftScale = leftType.getScale();
    int rightPrecision = rightType.getPrecision();
    int rightScale = rightType.getScale();

    int resultPrecision;
    int resultScale;

    switch (operation.toLowerCase()) {
      case "plus":
      case "add":
      case "minus":
      case "subtract":
        // 加减法：precision = max(p1-s1, p2-s2) + max(s1, s2) + 1
        //        scale = max(s1, s2)
        resultScale = Math.max(leftScale, rightScale);
        resultPrecision =
            Math.max(leftPrecision - leftScale, rightPrecision - rightScale) + resultScale + 1;
        break;

      case "multiply":
        // 乘法：precision = p1 + p2 + 1, scale = s1 + s2
        resultPrecision = leftPrecision + rightPrecision + 1;
        resultScale = leftScale + rightScale;
        break;

      case "divide":
        // 除法：precision = p1 - s1 + s2 + max(6, s1 + p2 + 1)
        //      scale = max(6, s1 + p2 + 1)
        resultScale = Math.max(6, leftScale + rightPrecision + 1);
        resultPrecision = leftPrecision - leftScale + rightScale + resultScale;
        break;

      default:
        // 默认情况，使用较大的精度和标度
        resultPrecision = Math.max(leftPrecision, rightPrecision);
        resultScale = Math.max(leftScale, rightScale);
        break;
    }

    // 确保精度不超过 38
    if (resultPrecision > 38) {
      resultPrecision = 38;
      // 调整 scale，确保不超过 precision
      resultScale = Math.min(resultScale, resultPrecision);
      // 对于除法，尽量保持至少 6 位小数
      if ("divide".equals(operation.toLowerCase())) {
        resultScale = Math.max(Math.min(resultScale, 6), resultPrecision - 32);
      }
    }

    // 确保 scale 不超过 precision
    resultScale = Math.min(resultScale, resultPrecision);

    return new DecimalType(resultPrecision, resultScale);
  }
}

class SubtractRexCallConverter extends BaseRexCallConverter {

  public SubtractRexCallConverter() {
    super("subtract");
  }

  @Override
  public TypedExpr toTypedExpr(RexCall callNode, RexConversionContext context) {
    List<TypedExpr> params = getParams(callNode, context);

    if (params.get(0).getReturnType() instanceof TimestampType
        && params.get(1).getReturnType() instanceof BigIntType) {

      Type bigIntType = new BigIntType();
      TypedExpr castExpr = new CallTypedExpr(bigIntType, List.of(params.get(0)), "cast");

      List<TypedExpr> newParams = List.of(castExpr, params.get(1));
      return new CallTypedExpr(bigIntType, newParams, functionName);
    }

    List<TypedExpr> alignedParams = TypeUtils.promoteTypeForArithmeticExpressions(params);
    Type resultType = getResultType(callNode);
    return new CallTypedExpr(resultType, alignedParams, functionName);
  }
}
