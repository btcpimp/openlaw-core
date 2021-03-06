package org.adridadou.openlaw.parser.template.variableTypes

import cats.implicits._
import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser._
import io.circe.syntax._
import org.adridadou.openlaw.OpenlawValue
import org.adridadou.openlaw.oracles.EthereumEventFilterExecution
import org.adridadou.openlaw.parser.template._
import org.adridadou.openlaw.parser.template.expressions.Expression
import org.adridadou.openlaw.parser.template.formatters.{
  Formatter,
  NoopFormatter
}
import org.adridadou.openlaw.result.{Failure, FailureException, Result, Success}

case object EthereumEventFilterType
    extends VariableType("EthereumEventFilter")
    with ActionType {
  implicit val smartContractEnc: Encoder[EventFilterDefinition] = deriveEncoder
  implicit val smartContractDec: Decoder[EventFilterDefinition] = deriveDecoder

  final case class EthereumEventPropertyDef(
      typeDef: VariableType,
      data: Seq[EthereumEventFilterExecution] => Result[Option[OpenlawValue]]
  )

  private val propertyDef: Map[String, EthereumEventPropertyDef] =
    Map[String, EthereumEventPropertyDef](
      "executionDate" -> EthereumEventPropertyDef(
        typeDef = DateTimeType,
        evts => Success(evts.headOption.map(_.executionDate))
      ),
      "tx" -> EthereumEventPropertyDef(
        typeDef = EthTxHashType,
        evts => Success(evts.headOption.map(_.event.hash))
      ),
      "received" -> EthereumEventPropertyDef(
        typeDef = YesNoType,
        evts => Success(Some(evts.nonEmpty))
      )
    )

  override def cast(
      value: String,
      executionResult: TemplateExecutionResult
  ): Result[EventFilterDefinition] =
    decode[EventFilterDefinition](value).leftMap(FailureException(_))

  override def internalFormat(value: OpenlawValue): Result[String] =
    value match {
      case call: EventFilterDefinition => Success(call.asJson.noSpaces)
    }

  private def propertyDef(
      key: VariableName,
      expr: Expression,
      executionResult: TemplateExecutionResult
  ): Result[EthereumEventPropertyDef] =
    expr
      .evaluate(executionResult)
      .flatMap { option =>
        option
          .map {
            case eventFilterDefinition: EventFilterDefinition =>
              eventFilterDefinition
                .abiOpenlawVariables(executionResult)
                .map(list =>
                  list
                    .find(_.name === key)
                    .map(definition => definition.varType(executionResult))
                ) match {
                case Success(Some(typeDef)) =>
                  Success(
                    EthereumEventPropertyDef(
                      typeDef = typeDef,
                      _.headOption
                        .map { execution =>
                          generateStructureType(
                            VariableName("none"),
                            eventFilterDefinition,
                            executionResult
                          ).flatMap { structure =>
                            structure
                              .cast(
                                execution.event.values.asJson.noSpaces,
                                executionResult
                              )
                              .flatMap(
                                structure.access(
                                  _,
                                  VariableName("none"),
                                  List(VariableMemberKey(key)),
                                  executionResult
                                )
                              )
                          }
                        }
                        .getOrElse(Success(None))
                    )
                  )
                case Success(None) =>
                  propertyDef.get(key.name) match {
                    case Some(pd) =>
                      Success(pd)
                    case None =>
                      Failure(s"unknown key $key for $expr")
                  }
                case Failure(ex, message) =>
                  Failure(ex, message)
              }

            case x =>
              Failure(
                s"unexpected value provided, expected EventFilterDefinition: $x"
              )
          }
          .getOrElse(
            Failure("the Ethereum event filter definition could not be found")
          )
      }

  override def keysType(
      keys: List[VariableMemberKey],
      expr: Expression,
      executionResult: TemplateExecutionResult
  ): Result[VariableType] =
    keys match {
      case VariableMemberKey(Left(VariableName(key))) :: Nil =>
        propertyDef.get(key) map { e =>
          Success(e.typeDef)
        } getOrElse Failure(s"unknown key $key for $name")
      case VariableMemberKey(Left(VariableName("event"))) :: VariableMemberKey(
            Left(variableName)
          ) :: Nil =>
        propertyDef(variableName, expr, executionResult).map(_.typeDef)
      case _ =>
        Failure(
          s"Ethereum event only support 'txt', 'received', 'executionDate' and 'event' invalid call with: ${keys
            .mkString(".")}"
        )
    }

  override def validateKeys(
      name: VariableName,
      keys: List[VariableMemberKey],
      expression: Expression,
      executionResult: TemplateExecutionResult
  ): Result[Unit] = keys match {
    case VariableMemberKey(Left(VariableName(key))) :: Nil =>
      propertyDef.get(key) map { _ =>
        Success.unit
      } getOrElse Failure(s"unknown key $key for $name")
    case VariableMemberKey(Left(VariableName("event"))) :: VariableMemberKey(
          Left(variableName)
        ) :: Nil =>
      propertyDef(variableName, name, executionResult).map(_ => ())
    case _ =>
      super.validateKeys(name, keys, expression, executionResult)
  }

  override def access(
      value: OpenlawValue,
      name: VariableName,
      keys: List[VariableMemberKey],
      executionResult: TemplateExecutionResult
  ): Result[Option[OpenlawValue]] =
    (for {
      actionDefinition <- VariableType.convert[EventFilterDefinition](value)
      identifier <- actionDefinition.identifier(executionResult)
    } yield {
      keys match {
        case Nil => Success(Some(value))
        case VariableMemberKey(Left(VariableName(key))) :: Nil =>
          propertyDef.get(key) match {
            case Some(pd) =>
              for {
                executions <- getExecutions(identifier, executionResult)
                result <- pd.data(executions)
              } yield result
            case None =>
              Failure(s"unknown key $key for $name")
          }
        case VariableMemberKey(Left(head)) :: tail if tail.isEmpty =>
          for {
            property <- propertyDef(head, name, executionResult)
            executions <- getExecutions(identifier, executionResult)
            result <- property.data(executions)
          } yield result
        case VariableMemberKey(Left(VariableName("event"))) :: VariableMemberKey(
              Left(head)
            ) :: Nil =>
          for {
            pd <- propertyDef(head, name, executionResult)
            executions <- getExecutions(identifier, executionResult)
            data <- pd.data(executions)
          } yield data
        case _ =>
          Failure(
            s"Ethereum event only support 'txt', 'received', 'executionDate' and 'event' invalid call with: ${keys
              .mkString(".")}"
          )
      }
    }).flatten

  private def getExecutions(
      identifier: ActionIdentifier,
      executionResult: TemplateExecutionResult
  ): Result[List[EthereumEventFilterExecution]] = {
    val x = executionResult.executions
      .get(identifier)
      .map(
        _.executionMap.values.toList
          .map(VariableType.convert[EthereumEventFilterExecution])
      )
      .getOrElse(Nil)
      .sequence
    x
  }

  override def defaultFormatter: Formatter = new NoopFormatter

  override def construct(
      constructorParams: Parameter,
      executionResult: TemplateExecutionResult
  ): Result[Option[EventFilterDefinition]] = {
    constructorParams match {
      case Parameters(v) =>
        val values = v.toMap
        for {
          contractAddress <- getExpression(values, "contract address")
          interface <- getExpression(values, "interface")
          eventType <- getExpression(values, "event type name")
          conditionalFilter <- getExpression(values, "conditional filter")
        } yield Some(
          EventFilterDefinition(
            contractAddress = contractAddress,
            interface = interface,
            eventType = eventType,
            conditionalFilter = conditionalFilter
          )
        )
      case _ =>
        Failure(
          "Ethereum event listener needs to get 'contract address', 'interface', 'event type name', 'conditional filter' as constructor parameter"
        )
    }
  }

  private def generateStructureType(
      name: VariableName,
      eventFilter: EventFilterDefinition,
      executionResult: TemplateExecutionResult
  ): Result[VariableType] = {
    eventFilter
      .abiOpenlawVariables(executionResult)
      .map(varDefinitions => {
        val typeDefinitions =
          varDefinitions.map(definition => definition.name -> definition)

        val types = varDefinitions.map(definition =>
          definition.name -> definition.varType(executionResult)
        )

        val structure = Structure(
          typeDefinition = typeDefinitions.toMap,
          types = types.toMap,
          names = typeDefinitions.map { case (k, _) => k }
        )
        AbstractStructureType.generateType(name, structure)
      })
  }

  override def getTypeClass: Class[_ <: EventFilterDefinition] =
    classOf[EventFilterDefinition]

  def thisType: VariableType = EthereumEventFilterType

  override def actionValue(value: OpenlawValue): Result[EventFilterDefinition] =
    VariableType.convert[EventFilterDefinition](value)
}
