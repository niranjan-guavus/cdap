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
import PropTypes from 'prop-types';
import KeyValueRow from 'components/AbstractWidget/KeyValueWidget/KeyValueRow';
import ThemeWrapper from 'components/ThemeWrapper';
import AbstractMultiRowWidget, {
  IMultiRowProps,
} from 'components/AbstractWidget/AbstractMultiRowWidget';
import { objectQuery } from 'services/helpers';
import { WIDGET_PROPTYPES } from 'components/AbstractWidget/constants';

interface IKeyValueWidgetProps {
  'key-placeholder'?: string;
  'value-placeholder'?: string;
  'kv-delimiter'?: string;
  delimiter?: string;
}

interface IKeyValueProps extends IMultiRowProps<IKeyValueWidgetProps> {
  isEncoded?: boolean; // for compatiblity with keyvalue-encoded type
}

class KeyValueWidgetView extends AbstractMultiRowWidget<IKeyValueProps> {
  public renderRow = (id, index) => {
    const keyPlaceholder = objectQuery(this.props, 'widgetProps', 'key-placeholder');
    const valuePlaceholder = objectQuery(this.props, 'widgetProps', 'value-placeholder');
    const kvDelimiter = objectQuery(this.props, 'widgetProps', 'kv-delimiter');
    const isEncoded = this.props.isEncoded || objectQuery(this.props, 'widgetProps', 'isEncoded');

    return (
      <KeyValueRow
        key={id}
        value={this.values[id].value}
        id={id}
        index={index}
        onChange={this.editRow}
        addRow={this.addRow.bind(this, index)}
        removeRow={this.removeRow.bind(this, index)}
        autofocus={this.state.autofocus === id}
        changeFocus={this.changeFocus}
        disabled={this.props.disabled}
        keyPlaceholder={keyPlaceholder}
        valuePlaceholder={valuePlaceholder}
        kvDelimiter={kvDelimiter}
        isEncoded={isEncoded}
        forwardedRef={this.values[id].ref}
      />
    );
  };
}

export default function KeyValueWidget(props) {
  return (
    <ThemeWrapper>
      <KeyValueWidgetView {...props} />
    </ThemeWrapper>
  );
}

(KeyValueWidget as any).propTypes = {
  ...WIDGET_PROPTYPES,
  isEncoded: PropTypes.bool,
};
