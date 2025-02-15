/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

import * as React from 'react';
import AbstractWidgetFactory from 'components/AbstractWidget/AbstractWidgetFactory';
import StateWrapper from 'components/AbstractWidget/StateWrapper';
require('./AbstractWidget.scss');

export const DEFAULT_WIDGET_PROPS = {
  widgetProps: {},
  value: '',
  // tslint:disable-next-line:no-empty
  onChange: () => {},
  disabled: false,
};

export interface IStageSchema {
  name: string;
  schema: string;
}

export interface IWidgetProps<T = any> {
  widgetProps: T;
  value: string | number;
  onChange: (value) => void | React.Dispatch<any>;
  extraConfig?: {
    namespace?: string;
    inputSchema?: IStageSchema[];
  };
  disabled?: boolean;
}

interface IAbstractWidgetProps extends IWidgetProps {
  type: string;
}

export default class AbstractWidget extends React.PureComponent<IAbstractWidgetProps> {
  public static defaultProps = DEFAULT_WIDGET_PROPS;

  public render() {
    const Comp = AbstractWidgetFactory[this.props.type];
    const { size = 'large' } = this.props.widgetProps;

    return (
      <div className={`abstract-widget-wrapper ${size}`}>
        <StateWrapper
          comp={Comp}
          onChange={this.props.onChange}
          value={this.props.value}
          widgetProps={this.props.widgetProps}
          extraConfig={this.props.extraConfig}
          disabled={this.props.disabled}
        />
      </div>
    );
  }
}
